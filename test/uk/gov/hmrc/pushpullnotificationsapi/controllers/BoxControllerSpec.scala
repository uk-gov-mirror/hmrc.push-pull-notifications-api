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

package uk.gov.hmrc.pushpullnotificationsapi.controllers

import java.util.UUID

import org.mockito.ArgumentMatchersSugar
import org.mockito.Mockito.{reset, verify, verifyNoInteractions, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.http.HeaderNames.{CONTENT_TYPE, USER_AGENT}
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.Result
import play.api.test.Helpers.{BAD_REQUEST, route, _}
import play.api.test.{FakeRequest, Helpers}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.pushpullnotificationsapi.config.AppConfig
import uk.gov.hmrc.pushpullnotificationsapi.controllers.actionbuilders.AuthAction
import uk.gov.hmrc.pushpullnotificationsapi.models.ResponseFormatters.boxFormats
import uk.gov.hmrc.pushpullnotificationsapi.models._
import uk.gov.hmrc.pushpullnotificationsapi.services.BoxService

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import uk.gov.hmrc.http.HeaderCarrier

class BoxControllerSpec extends UnitSpec with MockitoSugar with ArgumentMatchersSugar
  with GuiceOneAppPerSuite with BeforeAndAfterEach {

  implicit def mat: akka.stream.Materializer = app.injector.instanceOf[akka.stream.Materializer]

  val mockBoxService: BoxService = mock[BoxService]
  val mockAppConfig: AppConfig = mock[AppConfig]
  val mockAuthAction: AuthAction = mock[AuthAction]

  override lazy val app: Application = GuiceApplicationBuilder()
    .overrides(bind[BoxService].to(mockBoxService))
    .overrides(bind[AuthAction].to(mockAuthAction))
    .overrides(bind[AppConfig].to(mockAppConfig))
    .build()

  override def beforeEach(): Unit = {
    reset(mockBoxService, mockAppConfig)
  }

  private def setUpAppConfig(userAgents: List[String]): Unit = {
    when(mockAppConfig.whitelistedUserAgentList).thenReturn(userAgents)
  }

  val clientIdStr: String = UUID.randomUUID().toString
  val clientId: ClientId = ClientId(clientIdStr)
  val boxName: String = "boxName"

  val boxIdstr: String = UUID.randomUUID().toString
  val boxId: BoxId = BoxId(UUID.fromString(boxIdstr))
      val box: Box = Box(boxId, boxName, BoxCreator(clientId))
  val jsonBody: String =
    raw"""{"boxName": "$boxName",
         |"clientId": "$clientIdStr" }""".stripMargin

  def emptyJsonBody(boxNameVal: String = boxName, clientIdVal: String = clientIdStr): String =
     raw"""{"boxName": "$boxNameVal",
             |"clientId": "$clientIdVal" }""".stripMargin

  private val validHeadersWithValidUserAgent: Map[String, String] = Map(CONTENT_TYPE -> "application/json", USER_AGENT -> "api-subscription-fields")
  private val validHeadersWithInValidUserAgent: Map[String, String] = Map(CONTENT_TYPE -> "application/json", USER_AGENT -> "some-other-service")

  private val validHeadersWithInValidContentType: Map[String, String] = Map(CONTENT_TYPE -> "text/plain", USER_AGENT -> "api-subscription-fields")
  private val validHeadersWithEmptyContentType: Map[String, String] = Map(CONTENT_TYPE -> "", USER_AGENT -> "api-subscription-fields")

  private val validHeaders: Map[String, String] = Map(CONTENT_TYPE -> "application/json")


  "BoxController" when {
    "createBox" should {
      "return 201 and boxId when box successfully created" in {
        setUpAppConfig(List("api-subscription-fields"))
        when(mockBoxService.createBox(any[ClientId], any[String])(any[ExecutionContext], any[HeaderCarrier]))
          .thenReturn(Future.successful(BoxCreatedResult(box)))
        val result = await(doPut("/box", validHeadersWithValidUserAgent, jsonBody))
        status(result) should be(CREATED)
        val expectedBodyStr = s"""{"boxId":"${boxId.value}"}"""
        jsonBodyOf(result) should be (Json.parse(expectedBodyStr))

        verify(mockBoxService).createBox(eqTo(clientId), eqTo(boxName))(any[ExecutionContext], any[HeaderCarrier])
      }

      "return 200 and boxId when box already exists" in {
        setUpAppConfig(List("api-subscription-fields"))
        when(mockBoxService.createBox( any[ClientId], any[String])(any[ExecutionContext], any[HeaderCarrier]))
          .thenReturn(Future.successful(BoxRetrievedResult(box)))
        val result = await(doPut("/box", validHeadersWithValidUserAgent, jsonBody))
        status(result) should be(OK)
        val expectedBodyStr = s"""{"boxId":"${boxId.value}"}"""
        jsonBodyOf(result) should be(Json.parse(expectedBodyStr))

        verify(mockBoxService).createBox(eqTo(clientId), eqTo(boxName))(any[ExecutionContext], any[HeaderCarrier])
      }

      "return 400 when payload is completely invalid against expected format" in {
        setUpAppConfig(List("api-subscription-fields"))
        when(mockBoxService.createBox(any[ClientId], any[String])(any[ExecutionContext], any[HeaderCarrier]))
          .thenReturn(Future.successful(BoxCreatedResult(box)))
        val result = await(doPut("/box", validHeadersWithValidUserAgent, "{\"someOtherJson\":\"value\"}"))
        status(result) should be(BAD_REQUEST)
        val expectedBodyStr = s"""{"code":"INVALID_REQUEST_PAYLOAD","message":"JSON body is invalid against expected format"}"""
        jsonBodyOf(result) should be (Json.parse(expectedBodyStr))

        verifyNoInteractions(mockBoxService)
      }

     "return 400 when request payload is missing boxName" in {
        setUpAppConfig(List("api-subscription-fields"))
        when(mockBoxService.createBox(any[ClientId], any[String])(any[ExecutionContext], any[HeaderCarrier]))
          .thenReturn(Future.successful(BoxCreatedResult(box)))
        val result = await(doPut("/box", validHeadersWithValidUserAgent, emptyJsonBody(boxNameVal = "")))
        status(result) should be(BAD_REQUEST)
        val expectedBodyStr = s"""{"code":"INVALID_REQUEST_PAYLOAD","message":"Expecting boxName and clientId in request body"}"""
        jsonBodyOf(result) should be (Json.parse(expectedBodyStr))

        verifyNoInteractions(mockBoxService)
      }

      "return 415 when content type header is invalid" in {
        setUpAppConfig(List("api-subscription-fields"))
        when(mockBoxService.createBox(any[ClientId], any[String])(any[ExecutionContext], any[HeaderCarrier]))
          .thenReturn(Future.successful(BoxCreatedResult(box)))

        val result = await(doPut("/box",  validHeadersWithInValidContentType, jsonBody))
        status(result) should be(UNSUPPORTED_MEDIA_TYPE)

        verifyNoInteractions(mockBoxService)
      }


      "return 415 when content type header is empty" in {
        setUpAppConfig(List("api-subscription-fields"))
        when(mockBoxService.createBox(any[ClientId], any[String])(any[ExecutionContext], any[HeaderCarrier]))
          .thenReturn(Future.successful(BoxCreatedResult(box)))

        val result = await(doPut("/box",  validHeadersWithEmptyContentType, jsonBody))
        status(result) should be(UNSUPPORTED_MEDIA_TYPE)

        verifyNoInteractions(mockBoxService)
      }



      "return 422 when Left returned from Box Service" in {
        setUpAppConfig(List("api-subscription-fields"))
        when(mockBoxService.createBox(any[ClientId], any[String])(any[ExecutionContext], any[HeaderCarrier]))
          .thenReturn(Future.successful(BoxCreateFailedResult(s"Box with name :$boxName already exists for cleintId: $clientId but unable to retrieve")))
        val result = await(doPut("/box", validHeadersWithValidUserAgent, jsonBody))
        status(result) should be(UNPROCESSABLE_ENTITY)

        verify(mockBoxService).createBox(eqTo(clientId), eqTo(boxName))(any[ExecutionContext], any[HeaderCarrier])
      }

      "return 400 when useragent config is empty" in {
        setUpAppConfig(List.empty)
        val result = doPut("/box", validHeadersWithValidUserAgent, jsonBody)
        status(result) should be(BAD_REQUEST)

        verifyNoInteractions(mockBoxService)
      }


      "return 403 when invalid useragent provided" in {
        setUpAppConfig(List("api-subscription-fields"))
        val result = doPut("/box", validHeadersWithInValidUserAgent, jsonBody)
        status(result) should be(FORBIDDEN)

        verifyNoInteractions(mockBoxService)
      }

      "return 403 when no useragent header provided" in {
        setUpAppConfig(List("api-subscription-fields"))
        val result = doPut("/box", validHeaders, jsonBody)
        status(result) should be(FORBIDDEN)

        verifyNoInteractions(mockBoxService)
      }


      "return 500 when service fails with any runtime exception" in {
        setUpAppConfig(List("api-subscription-fields"))
        when(mockBoxService.createBox(any[ClientId], any[String])(any[ExecutionContext], any[HeaderCarrier]))
          .thenReturn(Future.failed(new RuntimeException("some error")))
        val result = doPut("/box", validHeadersWithValidUserAgent, jsonBody)
        status(result) should be(INTERNAL_SERVER_ERROR)

        verify(mockBoxService).createBox(eqTo(clientId), eqTo(boxName))(any[ExecutionContext], any[HeaderCarrier])
      }

      "return 400 when non JSon payload sent" in {
        setUpAppConfig(List("api-subscription-fields"))
        val result = doPut("/box", validHeaders, "xxx")
        status(result) should be(BAD_REQUEST)
        verifyNoInteractions(mockBoxService)
      }


      "return 400 when invalid JSon payload sent" in {
        setUpAppConfig(List("api-subscription-fields"))
        val result = doPut("/box", validHeadersWithValidUserAgent, "{}")
        status(result) should be(BAD_REQUEST)
        verifyNoInteractions(mockBoxService)
      }
    }

    "getBoxByNameAndClientId" should {

      "return 200 and requested box when it exists" in {

        when(mockBoxService.getBoxByNameAndClientId(eqTo(boxName), eqTo(clientId))(any[ExecutionContext]))
          .thenReturn(Future.successful(Some(Box(boxId = BoxId(UUID.randomUUID()), boxName = boxName, BoxCreator(clientId)))))

        val result: Result = await(doGet(s"/box?boxName=$boxName&clientId=${clientId.value}", validHeaders))

        status(result) should be(OK)

        verify(mockBoxService).getBoxByNameAndClientId(eqTo(boxName), eqTo(clientId))(any[ExecutionContext])
        val bodyVal = Helpers.contentAsString(result)
        val box = Json.parse(bodyVal).as[Box]
        box.subscriber.isDefined shouldBe false
      }

      "return 400 when no parameters provided" in {
        when(mockBoxService.getBoxByNameAndClientId(eqTo(boxName), eqTo(clientId))(any[ExecutionContext]))
          .thenReturn(Future.successful(Some(Box(boxId = BoxId(UUID.randomUUID()), boxName = boxName, BoxCreator(clientId)))))

        val result: Result = await(doGet(s"/box", validHeaders))
        status(result) should be(BAD_REQUEST)
        Helpers.contentAsString(result) shouldBe "{\"code\":\"BAD_REQUEST\",\"message\":\"Missing parameter: boxName\"}"
        verifyNoInteractions(mockBoxService)
      }

      "return 400 when boxName is missing" in {
        when(mockBoxService.getBoxByNameAndClientId(eqTo(boxName), eqTo(clientId))(any[ExecutionContext]))
          .thenReturn(Future.successful(Some(Box(boxId = BoxId(UUID.randomUUID()), boxName = boxName, BoxCreator(clientId)))))

        val result: Result = await(doGet(s"/box?clientId=$clientIdStr", validHeaders))
        status(result) should be(BAD_REQUEST)
        Helpers.contentAsString(result) shouldBe "{\"code\":\"BAD_REQUEST\",\"message\":\"Missing parameter: boxName\"}"
        verifyNoInteractions(mockBoxService)
      }

      "return 400 when clientId is missing" in {
        when(mockBoxService.getBoxByNameAndClientId(eqTo(boxName), eqTo(clientId))(any[ExecutionContext]))
          .thenReturn(Future.successful(Some(Box(boxId = BoxId(UUID.randomUUID()), boxName = boxName, BoxCreator(clientId)))))

        val result: Result = await(doGet(s"/box?boxName=$boxName", validHeaders))
        status(result) should be(BAD_REQUEST)
        Helpers.contentAsString(result) shouldBe "{\"code\":\"BAD_REQUEST\",\"message\":\"Missing parameter: clientId\"}"
        verifyNoInteractions(mockBoxService)
      }

      "return NOTFOUND when requested box does not exist" in {
        when(mockBoxService.getBoxByNameAndClientId(eqTo(boxName), eqTo(clientId))(any[ExecutionContext])).thenReturn(Future.successful(None))

        val result: Result = await(doGet(s"/box?boxName=$boxName&clientId=${clientId.value}", validHeaders))

        status(result) should be(NOT_FOUND)
        Helpers.contentAsString(result) shouldBe "{\"code\":\"BOX_NOT_FOUND\",\"message\":\"Box not found\"}"

        verify(mockBoxService).getBoxByNameAndClientId(eqTo(boxName), eqTo(clientId))(any[ExecutionContext])
      }
    }

     "addCallbackUrl" should {

      def createRequest(clientId: String, callBackUrl:String)= {
      raw"""
        |{
        |   "clientId": "$clientId",
        |   "callbackUrl": "$callBackUrl"
        |}
        |""".stripMargin
      }

      "return 200 when request is successful" in {
        setUpAppConfig(List("api-subscription-fields"))
        when(mockBoxService.updateCallbackUrl(eqTo(boxId), any[UpdateCallbackUrlRequest])(any[ExecutionContext], any[HeaderCarrier]))
          .thenReturn(Future.successful(CallbackUrlUpdated()))

        val result: Result =
          await(
            doPut(
              s"/box/${boxId.value}/callback",
              validHeadersWithValidUserAgent,
              createRequest("clientId", "callbackUrl")))

        status(result) should be(OK)
        Helpers.contentAsString(result) shouldBe """{"successful":true}"""
       }

       "return 200 when request contains " in {
         setUpAppConfig(List("api-subscription-fields"))
         when(mockBoxService.updateCallbackUrl(eqTo(boxId), any[UpdateCallbackUrlRequest])(any[ExecutionContext], any[HeaderCarrier]))
           .thenReturn(Future.successful(CallbackUrlUpdated()))

         val result: Result =
           await(
             doPut(
               s"/box/${boxId.value}/callback",
               validHeadersWithValidUserAgent,
               createRequest("clientId", "callbackUrl")))

         status(result) should be(OK)
         Helpers.contentAsString(result) shouldBe """{"successful":true}"""
       }

       "return 401 if User-Agent is not whitelisted" in {
          setUpAppConfig(List("api-subscription-fields"))
        when(mockBoxService.updateCallbackUrl(eqTo(boxId), any[UpdateCallbackUrlRequest])(any[ExecutionContext], any[HeaderCarrier]))
          .thenReturn(Future.successful(CallbackUrlUpdated()))

        val result: Result =
          await(
            doPut(
              s"/box/${boxId.value}/callback",
              validHeaders,
              createRequest("clientId", "callbackUrl")))

        status(result) should be(FORBIDDEN)
        Helpers.contentAsString(result) shouldBe """{"code":"FORBIDDEN","message":"Authorisation failed"}"""
       }

       "return 404 if Box does not exist" in {
         setUpAppConfig(List("api-subscription-fields"))
         when(mockBoxService.updateCallbackUrl(eqTo(boxId), any[UpdateCallbackUrlRequest])(any[ExecutionContext], any[HeaderCarrier]))
           .thenReturn(Future.successful(BoxIdNotFound()))

         val result: Result =
           await(
             doPut(
               s"/box/${boxId.value}/callback",
               validHeadersWithValidUserAgent,
               createRequest("clientId", "callbackUrl")))

         status(result) should be(NOT_FOUND)
       }

       "return 200, successful false and errormessage when mongo update fails" in {
         setUpAppConfig(List("api-subscription-fields"))
         val errorMessage = "Unable to update"
         when(mockBoxService.updateCallbackUrl(eqTo(boxId), any[UpdateCallbackUrlRequest])(any[ExecutionContext], any[HeaderCarrier]))
           .thenReturn(Future.successful(UnableToUpdateCallbackUrl(errorMessage)))

         val result: Result =
           await(
             doPut(
               s"/box/${boxId.value}/callback",
               validHeadersWithValidUserAgent,
               createRequest("clientId", "callbackUrl")))

         status(result) should be(OK)
         Helpers.contentAsString(result) shouldBe s"""{"successful":false,"errorMessage":"$errorMessage"}"""
       }

      "return 200, successful false and errormessage when callback validation fails" in {
        setUpAppConfig(List("api-subscription-fields"))
         val errorMessage = "Unable to update"
         when(mockBoxService.updateCallbackUrl(eqTo(boxId), any[UpdateCallbackUrlRequest])(any[ExecutionContext], any[HeaderCarrier]))
           .thenReturn(Future.successful(CallbackValidationFailed(errorMessage)))

         val result: Result =
           await(
             doPut(
               s"/box/${boxId.value}/callback",
               validHeadersWithValidUserAgent,
               createRequest("clientId", "callbackUrl")))

         status(result) should be(OK)
         Helpers.contentAsString(result) shouldBe s"""{"successful":false,"errorMessage":"$errorMessage"}"""
       }



       "return 401 if client id does not match that on the box" in {
         setUpAppConfig(List("api-subscription-fields"))
         when(mockBoxService.updateCallbackUrl(eqTo(boxId), any[UpdateCallbackUrlRequest])(any[ExecutionContext], any[HeaderCarrier]))
           .thenReturn(Future.successful(UpdateCallbackUrlUnauthorisedResult()))

         val result: Result =
           await(
             doPut(
               s"/box/${boxId.value}/callback",
               validHeadersWithValidUserAgent,
               createRequest("clientId", "callbackUrl")))

         status(result) should be(UNAUTHORIZED)
       }

      "return 400 when payload is non JSON" in {
          val result: Result = await(doPut(s"/box/5fc1f8e5-8881-4863-8a8c-5c897bb56815/callback", validHeadersWithValidUserAgent, "someBody"))

          status(result) should be(BAD_REQUEST)
          verifyNoInteractions(mockBoxService)
       }

      "return 400 when payload is missing the clientId value" in {
        setUpAppConfig(List("api-subscription-fields"))
          val result: Result =
            await(doPut(s"/box/5fc1f8e5-8881-4863-8a8c-5c897bb56815/callback", validHeadersWithValidUserAgent, createRequest("", "callbackUrl")))

          status(result) should be(BAD_REQUEST)
          Helpers.contentAsString(result) shouldBe """{"code":"INVALID_REQUEST_PAYLOAD","message":"clientId is required"}"""
          verifyNoInteractions(mockBoxService)
       }

       "return 200 when payload is missing the callbackUrl value" in {
          setUpAppConfig(List("api-subscription-fields"))
         when(mockBoxService.updateCallbackUrl(eqTo(boxId), any[UpdateCallbackUrlRequest])(any[ExecutionContext], any[HeaderCarrier]))
           .thenReturn(Future.successful(CallbackUrlUpdated()))
          val result: Result = await(doPut(s"/box/$boxIdstr/callback", validHeadersWithValidUserAgent, createRequest(clientIdStr, "")))

          status(result) should be(OK)
          Helpers.contentAsString(result) shouldBe """{"successful":true}"""
          verify(mockBoxService).updateCallbackUrl(eqTo(boxId), any[UpdateCallbackUrlRequest])(any[ExecutionContext], any[HeaderCarrier])
      }
     }
  }

  def doGet(uri: String, headers: Map[String, String]): Future[Result] = {
    val fakeRequest = FakeRequest(GET, uri).withHeaders(headers.toSeq: _*)
    route(app, fakeRequest).get
  }


  def doPut(uri: String, headers: Map[String, String], bodyValue: String): Future[Result] = {
    doPUT(uri, headers, bodyValue)
  }

  private def doPUT(uri: String, headers: Map[String, String], bodyValue: String): Future[Result] = {
    val maybeBody: Option[JsValue] = Try {
      Json.parse(bodyValue)
    } match {
      case Success(value) => Some(value)
      case Failure(_) => None
    }

    val fakeRequest = FakeRequest(PUT, uri).withHeaders(headers.toSeq: _*)
    maybeBody
      .fold(route(app, fakeRequest.withBody(bodyValue)).get)(jsonBody => route(app, fakeRequest.withJsonBody(jsonBody)).get)

  }
}
