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

package uk.gov.hmrc.pushpullnotificationsapi.services

import java.util.UUID

import org.joda.time.DateTime
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.mockito.captor.{ArgCaptor, Captor}
import org.mockito.stubbing.ScalaOngoingStubbing
import org.scalatest.BeforeAndAfterEach
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.pushpullnotificationsapi.models
import uk.gov.hmrc.pushpullnotificationsapi.models.notifications.{MessageContentType, Notification, NotificationId, NotificationStatus}
import uk.gov.hmrc.pushpullnotificationsapi.models._
import uk.gov.hmrc.pushpullnotificationsapi.repository.{BoxRepository, NotificationsRepository}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}


class NotificationsServiceSpec extends UnitSpec with MockitoSugar with ArgumentMatchersSugar with  BeforeAndAfterEach {

  private val mockBoxRepo = mock[BoxRepository]
  private val mockNotificationsRepo = mock[NotificationsRepository]
  private val mockNotificationsPushService = mock[NotificationPushService]
  val serviceToTest = new NotificationsService(mockBoxRepo, mockNotificationsRepo, mockNotificationsPushService)

  override def beforeEach(): Unit = {
    super.beforeEach()
    reset(mockBoxRepo, mockNotificationsRepo, mockNotificationsPushService)
  }

  trait Setup {

    val notificationCaptor: Captor[Notification] = ArgCaptor[Notification]

    // API-4417: Default the number of notifications
    when(mockNotificationsRepo.numberOfNotificationsToReturn).thenReturn(100)

    def primeNotificationRepoSave(result: Future[Option[NotificationId]]): ScalaOngoingStubbing[Future[Option[NotificationId]]] = {
      when(mockNotificationsRepo.saveNotification(any[Notification])(any[ExecutionContext])).thenReturn(result)
    }
    def primeNotificationRepoGetNotifications(result: Future[List[Notification]]): ScalaOngoingStubbing[Future[List[Notification]]] = {
      when(mockNotificationsRepo.getByBoxIdAndFilters(eqTo(boxId),
        any[Option[NotificationStatus]],
        any[Option[DateTime]],
        any[Option[DateTime]],
        any[Int])(any[ExecutionContext])).thenReturn(result)
    }

    def primeBoxRepo(result: Future[Option[Box]], boxId: BoxId): ScalaOngoingStubbing[Future[Option[Box]]] = {
      when(mockBoxRepo.findByBoxId(eqTo(boxId))(any[ExecutionContext])).thenReturn(result)
    }
  }

  private val boxIdStr = "ea69654b-9041-42c9-be5c-68dc11ecbcdf"
  private val boxId = models.BoxId(UUID.fromString(boxIdStr))
  private val clientIdStr = "b15b81ff-536b-4292-ae84-9466af9f3ab1"
  private val clientId = ClientId(clientIdStr)
  private val messageContentTypeJson = MessageContentType.APPLICATION_JSON
  private val messageContentTypeXml = MessageContentType.APPLICATION_XML
  private val message = "message"
  private val subscriber =  PushSubscriber("mycallbackUrl")
  private val BoxObjectWIthNoSubscribers = Box(boxId, "boxName", BoxCreator(clientId))
  private val BoxObjectWIthSubscribers = Box(boxId, "boxName", BoxCreator(clientId), subscriber = Some(subscriber))
  private implicit val hc: HeaderCarrier = HeaderCarrier()

  "SaveNotification" should {
    "return NotificationCreateSuccessResult when box exists , push is called with subscriber & notification successfully saved" in new Setup {
      primeBoxRepo(Future.successful(Some(BoxObjectWIthSubscribers)), boxId)
      primeNotificationRepoSave(Future.successful(Some(NotificationId(UUID.randomUUID()))))
      when(mockNotificationsPushService.handlePushNotification(eqTo(BoxObjectWIthSubscribers), any[Notification])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(true))
      val result: NotificationCreateServiceResult = await(serviceToTest.saveNotification(boxId,
        NotificationId(UUID.randomUUID()), messageContentTypeJson, message))
      result shouldBe NotificationCreateSuccessResult()

      verify(mockBoxRepo, times(1)).findByBoxId(eqTo(boxId))(any[ExecutionContext])
      verify(mockNotificationsPushService).handlePushNotification(eqTo(BoxObjectWIthSubscribers), any[Notification])(any[HeaderCarrier], any[ExecutionContext])
      validateNotificationSaved(notificationCaptor)
    }

    "return NotificationCreateSuccessResult when box exists, push is called with empty subscriber & notification successfully saved" in new Setup {
      primeBoxRepo(Future.successful(Some(BoxObjectWIthNoSubscribers)), boxId)
      primeNotificationRepoSave(Future.successful(Some(NotificationId(UUID.randomUUID()))))

      val result: NotificationCreateServiceResult = await(serviceToTest.saveNotification(boxId,
        NotificationId(UUID.randomUUID()), messageContentTypeJson, message))
      result shouldBe NotificationCreateSuccessResult()

      verify(mockBoxRepo, times(1)).findByBoxId(eqTo(boxId))(any[ExecutionContext])
      verify(mockNotificationsPushService)
        .handlePushNotification(eqTo(BoxObjectWIthNoSubscribers), any[Notification])(any[HeaderCarrier], any[ExecutionContext])
      validateNotificationSaved(notificationCaptor)
    }

    "return NotificationCreateFailedBoxNotFoundResult when box does not exist" in new Setup {
      primeBoxRepo(Future.successful(None), boxId)

      val result: NotificationCreateServiceResult =
        await(serviceToTest.saveNotification(boxId, NotificationId(UUID.randomUUID()), messageContentTypeXml, message))
      result shouldBe NotificationCreateFailedBoxIdNotFoundResult(s"BoxId: BoxId($boxIdStr) not found")

      verify(mockBoxRepo, times(1)).findByBoxId(eqTo(boxId))(any[ExecutionContext])
      verifyZeroInteractions(mockNotificationsRepo)
    }

    def validateNotificationSaved(notificationCaptor: Captor[Notification] ): Unit ={
      verify(mockNotificationsRepo).saveNotification(notificationCaptor.capture)(any[ExecutionContext])
      notificationCaptor.value.boxId shouldBe boxId
      notificationCaptor.value.messageContentType shouldBe messageContentTypeJson
      notificationCaptor.value.message shouldBe message
    }
  }

  "getNotifications" should {

    val status = Some(NotificationStatus.PENDING)
    val fromDate = Some(DateTime.now().minusHours(2))
    val toDate = Some(DateTime.now())


    "return list of matched notifications" in new Setup {

      primeBoxRepo(Future.successful(Some(BoxObjectWIthNoSubscribers)), boxId)
      primeNotificationRepoGetNotifications(
        Future.successful(List(Notification(NotificationId(UUID.randomUUID()),
          boxId,MessageContentType.APPLICATION_JSON, "{}", status.head, toDate.head)))
      )
     val result: GetNotificationCreateServiceResult =  await(serviceToTest.getNotifications(boxId= boxId,
        clientId = clientId,
        status = status,
        fromDateTime = fromDate,
        toDateTime = toDate))

      val resultsList : GetNotificationsSuccessRetrievedResult = result.asInstanceOf[GetNotificationsSuccessRetrievedResult]
      resultsList.notifications.isEmpty shouldBe false

      verify(mockNotificationsRepo).getByBoxIdAndFilters(eqTo(boxId), eqTo(status), eqTo(fromDate), eqTo(toDate), anyInt)(any[ExecutionContext])

    }

    "return empty list when no notifications found" in new Setup {

      primeBoxRepo(Future.successful(Some(BoxObjectWIthNoSubscribers)), boxId)
      primeNotificationRepoGetNotifications(Future.successful(List.empty))

      val result: GetNotificationCreateServiceResult =  await(serviceToTest.getNotifications(boxId= boxId,
        clientId = clientId,
        status = status,
        fromDateTime = fromDate,
        toDateTime = toDate))

      result shouldBe GetNotificationsSuccessRetrievedResult(List.empty)

      verify(mockNotificationsRepo).getByBoxIdAndFilters(eqTo(boxId), eqTo(status), eqTo(fromDate), eqTo(toDate), anyInt)(any[ExecutionContext])
    }

    "return notfound exception when client id is different from box creator client id" in new Setup {

      primeBoxRepo(Future.successful(Some(BoxObjectWIthNoSubscribers)), boxId)
      primeNotificationRepoGetNotifications(Future.successful(List.empty))

      val result: GetNotificationCreateServiceResult = await(serviceToTest.getNotifications(boxId = boxId,
          clientId = ClientId(UUID.randomUUID().toString),
          status = status,
          fromDateTime = fromDate,
          toDateTime = toDate))

      result shouldBe GetNotificationsServiceUnauthorisedResult("clientId does not match boxCreator")

      verifyNoMoreInteractions(mockNotificationsRepo)
    }
  }



  "Acknowledge notifications" should {
    "return AcknowledgeNotificationsSuccessUpdatedResult when repo returns true" in new Setup {
      primeBoxRepo(Future.successful(Some(BoxObjectWIthNoSubscribers)), boxId)
      runAcknowledgeScenarioAndAssert(AcknowledgeNotificationsSuccessUpdatedResult(true), repoResult = Future.successful(true))
    }

    "return AcknowledgeNotificationsSuccessUpdatedResult when repo returns false" in new Setup {
      primeBoxRepo(Future.successful(Some(BoxObjectWIthNoSubscribers)), boxId)
      runAcknowledgeScenarioAndAssert(AcknowledgeNotificationsSuccessUpdatedResult(false))
    }

    "return AcknowledgeNotificationsServiceBoxNotFoundResult when box not found" in new Setup {
      primeBoxRepo(Future.successful(None), boxId)
      runAcknowledgeScenarioAndAssert(AcknowledgeNotificationsServiceBoxNotFoundResult(s"BoxId: BoxId(${boxId.raw}) not found"))
    }

    "return AcknowledgeNotificationsServiceUnauthorisedResult when caller is not owner of box" in new Setup {
      primeBoxRepo(Future.successful(Some(BoxObjectWIthNoSubscribers)), boxId)
      runAcknowledgeScenarioAndAssert(AcknowledgeNotificationsServiceUnauthorisedResult("clientId does not match boxCreator"),
        ClientId("notTheCLientID"))
    }

  }

  def runAcknowledgeScenarioAndAssert(expectedResult: AcknowledgeNotificationsServiceResult, clientId: ClientId = clientId,
                                      repoResult: Future[Boolean] = Future.successful(false)) : Unit = {
    when(mockNotificationsRepo.acknowledgeNotifications(any[BoxId], any[List[String]])(any[ExecutionContext]))
      .thenReturn(repoResult)
    val result: AcknowledgeNotificationsServiceResult =
      await(serviceToTest.acknowledgeNotifications(boxId, clientId, AcknowledgeNotificationsRequest(List("123455"))))
    result shouldBe expectedResult
  }
}
