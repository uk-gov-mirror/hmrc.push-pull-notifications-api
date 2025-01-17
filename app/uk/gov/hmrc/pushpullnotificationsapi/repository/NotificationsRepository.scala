/*
 * Copyright 2021 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.pushpullnotificationsapi.repository

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import org.joda.time.DateTime.now
import org.joda.time.DateTimeZone.UTC
import play.api.Logger
import play.api.libs.json._
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.akkastream.cursorProducer
import reactivemongo.api.Cursor.FailOnError
import reactivemongo.api.ReadPreference
import reactivemongo.api.commands.{UpdateWriteResult, WriteResult}
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.{BSONDocument, BSONLong, BSONObjectID}
import reactivemongo.play.json.ImplicitBSONHandlers.JsObjectDocumentWriter
import uk.gov.hmrc.crypto.CompositeSymmetricCrypto
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.pushpullnotificationsapi.config.AppConfig
import uk.gov.hmrc.pushpullnotificationsapi.models.SubscriptionType.API_PUSH_SUBSCRIBER
import uk.gov.hmrc.pushpullnotificationsapi.models._
import uk.gov.hmrc.pushpullnotificationsapi.models.notifications.NotificationStatus.{ACKNOWLEDGED, PENDING}
import uk.gov.hmrc.pushpullnotificationsapi.models.notifications.{Notification, NotificationId, NotificationStatus, RetryableNotification}
import uk.gov.hmrc.pushpullnotificationsapi.repository.models.DbNotification.{fromNotification, toNotification}
import uk.gov.hmrc.pushpullnotificationsapi.repository.models.DbRetryableNotification.toRetryableNotification
import uk.gov.hmrc.pushpullnotificationsapi.repository.models.ReactiveMongoFormatters.dbNotificationFormatter
import uk.gov.hmrc.pushpullnotificationsapi.repository.models.{DbNotification, DbRetryableNotification}
import uk.gov.hmrc.pushpullnotificationsapi.util.mongo.IndexHelper._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class NotificationsRepository @Inject()(appConfig: AppConfig, mongoComponent: ReactiveMongoComponent, crypto: CompositeSymmetricCrypto)
                                       (implicit ec: ExecutionContext, mat: Materializer)
  extends ReactiveRepository[DbNotification, BSONObjectID](
    "notifications",
    mongoComponent.mongoConnector.db,
    dbNotificationFormatter,
    ReactiveMongoFormats.objectIdFormats) with ReactiveMongoFormats {

  private lazy val create_datetime_ttlIndexName = "create_datetime_ttl_idx"
  private lazy val notifications_index_name = "notifications_idx"
  private lazy val created_datetime_index_name = "notifications_created_datetime_idx"
  private lazy val OptExpireAfterSeconds = "expireAfterSeconds"

  lazy val numberOfNotificationsToReturn: Int = appConfig.numberOfNotificationsToRetrievePerRequest

  //API-4370 need to delete old indexes this code can be removed once this has been run
  private lazy val oldIndexes: List[String] = List("notifications_index", "notificationsDateRange_index", "notifications_created_datetime_index")

  override def indexes = Seq(
    createAscendingIndex(
      Some(notifications_index_name),
      isUnique = true,
      isBackground = true,
      List("notificationId", "boxId", "status"): _*
    ),
    createAscendingIndex(
      Some(created_datetime_index_name),
      isUnique = false,
      isBackground = true,
      List("boxId, createdDateTime"): _*
    ),
    Index(
      key = Seq("createdDateTime" -> IndexType.Ascending),
      name = Some(create_datetime_ttlIndexName),
      background = true,
      options = BSONDocument(OptExpireAfterSeconds -> BSONLong(appConfig.notificationTTLinSeconds))
    )
  )

  override def ensureIndexes(implicit ec: ExecutionContext): Future[Seq[Boolean]] = {
    super.ensureIndexes
    dropOldIndexes
    dropTTLIndexIfChanged
    ensureLocalIndexes()
  }

  private def ensureLocalIndexes()(implicit ec: ExecutionContext) = {
    Future.sequence(indexes.map(index => {
      Logger.info(s"ensuring index ${index.eventualName}")
      collection.indexesManager.ensure(index)
    }))
  }

  private def dropOldIndexes()(implicit ec: ExecutionContext): List[Future[Int]] = {
    //API-4370 need to delete old indexes this code can be removed once this has been run
    oldIndexes.map(idxName => collection.indexesManager.drop(idxName))
  }

  private def dropTTLIndexIfChanged(implicit ec: ExecutionContext) = {
    val indexes = collection.indexesManager.list()

    def matchIndexName(index: Index) = {
      index.eventualName == create_datetime_ttlIndexName
    }

    def compareTTLValueWithConfig(index: Index) = {
      index.options.getAs[BSONLong](OptExpireAfterSeconds).fold(false)(_.as[Long] != appConfig.notificationTTLinSeconds)
    }

    def checkIfTTLChanged(index: Index): Boolean = {
      matchIndexName(index) && compareTTLValueWithConfig(index)
    }

    indexes.map(_.exists(checkIfTTLChanged))
      .map(hasTTLIndexChanged => if (hasTTLIndexChanged) {
        Logger.info(s"Dropping time to live index for entries in ${collection.name}")
        Future.sequence(Seq(collection.indexesManager.drop(create_datetime_ttlIndexName).map(_ > 0),
          ensureLocalIndexes))
      })

  }

  def getByBoxIdAndFilters(boxId: BoxId,
                           status: Option[NotificationStatus] = None,
                           fromDateTime: Option[DateTime] = None,
                           toDateTime: Option[DateTime] = None,
                           numberOfNotificationsToReturn: Int = numberOfNotificationsToReturn)
                          (implicit ec: ExecutionContext): Future[List[Notification]] = {

    val query: JsObject =
      Json.obj(f"$$and" -> (
        boxIdQuery(boxId) ++
          statusQuery(status) ++
          Json.arr(dateRange("createdDateTime", fromDateTime, toDateTime))))

    collection
      .find(query, Option.empty[DbNotification])
      .sort(Json.obj("createdDateTime" -> 1))
      .cursor[DbNotification](ReadPreference.primaryPreferred)
      .collect(maxDocs = numberOfNotificationsToReturn, FailOnError[List[DbNotification]]())
      .map(_.map(toNotification(_, crypto)))
  }

  val empty: JsObject = Json.obj()

  private def dateRange(fieldName: String, start: Option[DateTime], end: Option[DateTime]): JsObject = {
    if (start.isDefined || end.isDefined) {
      val startCompare = if (start.isDefined) Json.obj("$gte" -> Json.obj("$date" -> start.get.getMillis)) else empty
      val endCompare = if (end.isDefined) Json.obj("$lte" -> Json.obj("$date" -> end.get.getMillis)) else empty
      Json.obj(fieldName -> (startCompare ++ endCompare))
    }
    else empty
  }

  private def boxIdQuery(boxId: BoxId): JsArray = {
    Json.arr(Json.obj("boxId" -> boxId.value))
  }

  private def notificationIdsQuery(notificationIds: List[String]): JsArray = {
    Json.arr(Json.obj("notificationId" -> Json.obj("$in" -> notificationIds)))
  }

  private def statusQuery(maybeStatus: Option[NotificationStatus]): JsArray = {
    maybeStatus.fold(Json.arr()) { status => Json.arr(Json.obj("status" -> status)) }
  }

  def getAllByBoxId(boxId: BoxId)
                   (implicit ec: ExecutionContext): Future[List[Notification]] = getByBoxIdAndFilters(boxId, numberOfNotificationsToReturn = Int.MaxValue)

  def saveNotification(notification: Notification)(implicit ec: ExecutionContext): Future[Option[NotificationId]] =

    insert(fromNotification(notification, crypto)).map(_ => Some(notification.notificationId)).recoverWith {
      case e: WriteResult if e.code.contains(MongoErrorCodes.DuplicateKey) =>
        Future.successful(None)
    }

  def acknowledgeNotifications(boxId: BoxId, notificationIds: List[String])(implicit ec: ExecutionContext): Future[Boolean] = {

    val query = Json.obj(f"$$and" -> (
      boxIdQuery(boxId) ++
        notificationIdsQuery(notificationIds)))

    collection
      .update(false)
      .one(query,
        Json.obj("$set" -> Json.obj("status" -> ACKNOWLEDGED)),
        upsert = false,
        multi = true)
      .map((result: UpdateWriteResult) => {

        if(result.nModified!=notificationIds.size){
          Logger.warn(s"for boxId: ${boxId.raw} ${notificationIds.size} requested to be Acknowledged but only ${result.nModified} were modified")
        }
        result.ok
      })

  }

  def updateStatus(notificationId: NotificationId, newStatus: NotificationStatus): Future[Notification] = {
    updateNotification(notificationId, Json.obj("$set" -> Json.obj("status" -> newStatus)))
  }

  def updateRetryAfterDateTime(notificationId: NotificationId, newRetryAfterDateTime: DateTime): Future[Notification] = {
    updateNotification(notificationId, Json.obj("$set" -> Json.obj("retryAfterDateTime" -> newRetryAfterDateTime)))
  }

  private def updateNotification(notificationId: NotificationId, updateStatement: JsObject): Future[Notification] =
    findAndUpdate(Json.obj("notificationId" -> notificationId.value), updateStatement, fetchNewObject = true) map { updated =>
      toNotification(updated.result[DbNotification].head, crypto)
    }

  def fetchRetryableNotifications: Source[RetryableNotification, Future[Any]] = {
    import uk.gov.hmrc.pushpullnotificationsapi.repository.models.ReactiveMongoFormatters.dbRetryableNotificationFormatter

    val builder = collection.BatchCommands.AggregationFramework
    val pipeline = List(
      builder.Match(Json.obj("$and" -> Json.arr(Json.obj("status" -> PENDING),
        Json.obj("$or" -> Json.arr(Json.obj("retryAfterDateTime" -> Json.obj("$lte" -> now(UTC))),
          Json.obj("retryAfterDateTime" -> Json.obj("$exists" -> false))))))),
      builder.Lookup(from = "box", localField = "boxId", foreignField = "boxId", as = "boxes"),
      builder.Match(Json.obj("$and" -> Json.arr(Json.obj("boxes.subscriber.subscriptionType" -> API_PUSH_SUBSCRIBER),
        Json.obj("boxes.subscriber.callBackUrl" -> Json.obj("$exists" -> true, "$ne" -> ""))))),
      builder.Project(
        Json.obj("notification" -> Json.obj("notificationId" -> "$notificationId", "boxId" -> "$boxId", "messageContentType" -> "$messageContentType",
          "message" -> "$message", "encryptedMessage" -> "$encryptedMessage", "status" -> "$status", "createdDateTime" -> "$createdDateTime",
          "retryAfterDateTime" -> "$retryAfterDateTime"),
        "box" -> Json.obj("$arrayElemAt" -> JsArray(Seq(JsString("$boxes"), JsNumber(0))))
      ))
    )

    collection.aggregateWith[DbRetryableNotification]()(_ => (pipeline.head, pipeline.tail))
      .documentSource()
      .map(toRetryableNotification(_, crypto))
  }
}
