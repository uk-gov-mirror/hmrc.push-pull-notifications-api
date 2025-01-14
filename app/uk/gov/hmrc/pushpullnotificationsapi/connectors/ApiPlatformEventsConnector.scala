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

package uk.gov.hmrc.pushpullnotificationsapi.connectors

import java.util.UUID
import java.util.UUID.randomUUID

import com.google.inject.Inject
import controllers.Assets.CREATED
import javax.inject.Singleton
import org.joda.time.DateTime
import play.api.Logger
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.pushpullnotificationsapi.config.AppConfig
import uk.gov.hmrc.pushpullnotificationsapi.connectors.ApiPlatformEventsConnector.{EventId, PpnsCallBackUriUpdatedEvent}
import uk.gov.hmrc.pushpullnotificationsapi.models.ConnectorFormatters._
import uk.gov.hmrc.pushpullnotificationsapi.models.{ApplicationId, Box}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class ApiPlatformEventsConnector @Inject()(http: HttpClient, appConfig: AppConfig)(implicit ec: ExecutionContext) {


  def sendCallBackUpdatedEvent(applicationId: ApplicationId, oldUrl: String, newUrl: String, box: Box)(implicit hc: HeaderCarrier): Future[Boolean] = {
    val url = s"${appConfig.apiPlatformEventsUrl}/application-events/ppnsCallbackUriUpdated"
    val event = PpnsCallBackUriUpdatedEvent(EventId.random, applicationId.value, DateTime.now(), oldUrl, newUrl, box.boxId.raw, box.boxName)
    http.POST(url, event)
      .map(_.status == CREATED)
      .recoverWith {
        case NonFatal(e) =>
          Logger.info("exception calling api platform events", e)
          Future.successful(false)
      }
  }
}

object ApiPlatformEventsConnector {

  case class EventId(value: UUID) extends AnyVal
  object EventId {
    def random: EventId = EventId(randomUUID())
  }

  //This is hardcoded at the moment as we dont have the details of the user who initiated the callback change
  case class Actor(id: String = "", actorType: String = "UNKNOWN")

  case class PpnsCallBackUriUpdatedEvent(id: EventId,
                                         applicationId: String,
                                         eventDateTime: DateTime,
                                         oldCallbackUrl: String,
                                         newCallbackUrl: String,
                                         boxId: String,
                                         boxName: String,
                                         actor: Actor = Actor()) {
    val eventType = "PPNS_CALLBACK_URI_UPDATED"
  }
}
