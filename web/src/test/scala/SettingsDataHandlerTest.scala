package tela.web

import akka.actor.ActorRef
import akka.testkit.TestActor.NoAutoPilot
import akka.testkit.TestActorRef
import io.netty.handler.codec.http.{ClientCookieEncoder, HttpHeaders, HttpMethod}
import org.junit.Assert._
import org.junit.{Before, Test}
import org.mashupbots.socko.events.HttpResponseStatus
import tela.web.JSONConversions._
import tela.web.SessionManager.{ChangePassword, GetLanguages, SetLanguage}
import tela.web.SettingsDataHandler._

class SettingsDataHandlerTest extends SockoHandlerTestBase {
  @Before def initialize(): Unit = {
    doBasicInitialization()
  }

  @Test def changePasswordSuccess(): Unit = {
    attemptPasswordChangeAndAssertResponse(true, HttpResponseStatus.OK)
  }

  @Test def changePasswordFailure(): Unit = {
    attemptPasswordChangeAndAssertResponse(false, HttpResponseStatus.PRECONDITION_FAILED)
  }

  private def attemptPasswordChangeAndAssertResponse(resultOfPasswordChange: Boolean, expectedResponse: HttpResponseStatus): Unit = {
    handler = TestActorRef(new SettingsDataHandler(sessionManagerProbe.ref, Password))
    initializeTestProbe(true, (sender: ActorRef) => {
      case ChangePassword(TestSessionId, "old", "new") =>
        sender ! resultOfPasswordChange
        NoAutoPilot
    })

    val event = createHttpRequestEvent(HttpMethod.PUT, "/settings/" + Password,
      Map(HttpHeaders.Names.CONTENT_TYPE -> "application/x-www-form-urlencoded", HttpHeaders.Names.COOKIE -> ClientCookieEncoder.encode(createCookie(SessionIdCookieName, TestSessionId))),
      JSONConversions.OldPasswordKey + "=" + "old" + "&" + JSONConversions.NewPasswordKey + "=" + "new")

    handler ! event

    assertEquals(expectedResponse, event.response.status)
    assertResponseBody("")
  }

  @Test def setLanguage(): Unit = {
    handler = TestActorRef(new SettingsDataHandler(sessionManagerProbe.ref, Language))
    var languageChanged = false
    initializeTestProbe(true, (sender: ActorRef) => {
      case SetLanguage(TestSessionId, "es") =>
        languageChanged = true
        NoAutoPilot
    })

    val event = createHttpRequestEvent(HttpMethod.PUT, "/settings/" + Language,
      Map(HttpHeaders.Names.CONTENT_TYPE -> "application/x-www-form-urlencoded", HttpHeaders.Names.COOKIE -> ClientCookieEncoder.encode(createCookie(SessionIdCookieName, TestSessionId))),
      JSONConversions.LanguageKey + "=" + "es")

    handler ! event

    assertTrue(languageChanged)
    assertEquals(HttpResponseStatus.OK, event.response.status)
    assertResponseBody("")
  }

  @Test def getLanguages(): Unit = {
    handler = TestActorRef(new SettingsDataHandler(sessionManagerProbe.ref, Languages))
    initializeTestProbe(true, (sender: ActorRef) => {
      case GetLanguages(TestSessionId) =>
        sender ! LanguageInfo(Map("en" -> "English", "es" -> "Español"), "en")
        NoAutoPilot
    })

    val event = createHttpRequestEvent(HttpMethod.GET, "/settings/" + Languages,
      Map(HttpHeaders.Names.COOKIE -> ClientCookieEncoder.encode(createCookie(SessionIdCookieName, TestSessionId))))

    handler ! event

    assertEquals(HttpResponseStatus.OK, event.response.status)
    assertResponseBody( s"""{"$LanguagesKey":{"en":"English","es":"Español"},"$SelectedLanguageKey":"en"}""")
    assertEquals(JsonContentType, event.response.contentType.get)
  }
}
