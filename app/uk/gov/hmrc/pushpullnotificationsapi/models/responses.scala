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

package uk.gov.hmrc.pushpullnotificationsapi.models

import org.joda.time.{DateTime, DateTimeZone}
import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json.{JsObject, Json}
import uk.gov.hmrc.pushpullnotificationsapi.models.notifications.NotificationStatus.PENDING
import uk.gov.hmrc.pushpullnotificationsapi.models.notifications.{MessageContentType, Notification, NotificationId, NotificationStatus}

case class CreateBoxResponse(boxId: String)
case class CreateNotificationResponse(notificationId: String)

case class UpdateCallbackUrlResponse(successful: Boolean, errorMessage: Option[String] = None)

case class NotificationResponse(notificationId: NotificationId,
                                boxId: BoxId,
                                messageContentType: MessageContentType,
                                message: String,
                                status: NotificationStatus = PENDING,
                                createdDateTime: DateTime = DateTime.now(DateTimeZone.UTC),
                                readDateTime: Option[DateTime] = None,
                                pushedDateTime: Option[DateTime] = None)

object NotificationResponse {
  def fromNotification(notification: Notification): NotificationResponse = {
    NotificationResponse(notification.notificationId, notification.boxId, notification.messageContentType, notification.message,
      notification.status, notification.createdDateTime, notification.readDateTime, notification.pushedDateTime)
  }
}

object ErrorCode extends Enumeration {
  type ErrorCode = Value
  val ACCEPT_HEADER_INVALID = Value("ACCEPT_HEADER_INVALID")
  val BAD_REQUEST = Value("BAD_REQUEST")
  val BOX_NOT_FOUND = Value("BOX_NOT_FOUND")
  val CLIENT_NOT_FOUND = Value("CLIENT_NOT_FOUND")
  val DUPLICATE_BOX = Value("DUPLICATE_BOX")
  val DUPLICATE_NOTIFICATION = Value("DUPLICATE_NOTIFICATION")
  val FORBIDDEN = Value("FORBIDDEN")
  val INVALID_ACCEPT_HEADER = Value("INVALID_ACCEPT_HEADER")
  val INVALID_CONTENT_TYPE =Value("INVALID_CONTENT_TYPE")
  val INVALID_REQUEST_PAYLOAD = Value("INVALID_REQUEST_PAYLOAD")
  val NOT_FOUND = Value("NOT_FOUND")
  val UNAUTHORISED = Value("UNAUTHORISED")
  val UNKNOWN_ERROR = Value("UNKNOWN_ERROR")

}

object JsErrorResponse {
  def apply(errorCode: ErrorCode.Value, message: JsValueWrapper): JsObject =
    Json.obj(
      "code" -> errorCode.toString,
      "message" -> message
    )
}
