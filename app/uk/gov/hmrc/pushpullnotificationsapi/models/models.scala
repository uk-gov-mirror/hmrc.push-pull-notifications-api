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

import java.util.UUID

import enumeratum.{Enum, EnumEntry, PlayJsonEnum}
import org.joda.time.{DateTime, DateTimeZone}
import uk.gov.hmrc.pushpullnotificationsapi.models.SubscriptionType.{API_PULL_SUBSCRIBER, API_PUSH_SUBSCRIBER}

import scala.collection.immutable

case class BoxId(value: UUID) extends AnyVal {
  def raw: String = value.toString
}

case class ClientId(value: String) extends AnyVal

case class ApplicationId(value: String) extends AnyVal

case class BoxCreator(clientId: ClientId)

sealed trait SubscriptionType extends EnumEntry

object SubscriptionType extends Enum[SubscriptionType] with PlayJsonEnum[SubscriptionType]  {
   val values: immutable.IndexedSeq[SubscriptionType] = findValues

  case object API_PUSH_SUBSCRIBER extends SubscriptionType
  case object API_PULL_SUBSCRIBER extends SubscriptionType // Does this need to exist?
}

sealed trait Subscriber {
  val subscribedDateTime: DateTime
  val subscriptionType: SubscriptionType
}

class SubscriberContainer[+A] (val elem: A)

case class PushSubscriber(callBackUrl: String,
                          override val subscribedDateTime: DateTime = DateTime.now(DateTimeZone.UTC)) extends Subscriber {
  override val subscriptionType: SubscriptionType = API_PUSH_SUBSCRIBER
}

case class PullSubscriber(callBackUrl: String, // Remove callbackUrl
                          override val subscribedDateTime: DateTime = DateTime.now(DateTimeZone.UTC)) extends Subscriber {
  override val subscriptionType: SubscriptionType = API_PULL_SUBSCRIBER
}

case class Box(boxId: BoxId,
               boxName: String,
               boxCreator: BoxCreator,
               applicationId: Option[ApplicationId] = None,
               subscriber: Option[Subscriber] = None) // Default to PullSubscriber?

case class Client(id: ClientId, secrets: Seq[ClientSecret])
case class ClientSecret(value: String)
