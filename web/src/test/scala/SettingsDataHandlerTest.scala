package tela.web

import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import akka.testkit.TestActor.NoAutoPilot
import org.junit.Assert._
import org.junit.{Before, Test}
import play.api.http.{ContentTypes, Status}
import play.api.mvc._
import play.api.test.FakeRequest
import play.api.test.Helpers._
import tela.web.JSONConversions._
import tela.web.SessionManager.{ChangePassword, GetLanguages, SetLanguage}

import scala.concurrent.duration.Duration

class SettingsDataHandlerTest extends SessionManagerClientTestBase {
  private implicit val timeout = Duration(TimeoutDurationInSeconds, TimeUnit.SECONDS)

  @Before def initialize(): Unit = {
    doBasicInitialization()
  }

  @Test def changePasswordSuccess(): Unit = {
    attemptPasswordChangeAndAssertResponse(true, Status.OK)
  }

  @Test def changePasswordFailure(): Unit = {
    attemptPasswordChangeAndAssertResponse(false, Status.BAD_REQUEST)
  }

  private def attemptPasswordChangeAndAssertResponse(resultOfPasswordChange: Boolean, expectedStatus: Int): Unit = {
    initializeTestProbe(true, (sender: ActorRef) => {
      case ChangePassword(TestSessionId, "old", "new") =>
        sender ! resultOfPasswordChange
        NoAutoPilot
    })

    val controller = new SettingsController(new UserAction(sessionManagerProbe.ref), sessionManagerProbe.ref)
    val result = controller.changePassword.apply(
      FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)).withBody(SettingsController.ChangePasswordRequest("old", "new")))

    assertEquals(expectedStatus, status(result))
  }

  @Test def setLanguage(): Unit = {
    var languageChanged = false
    initializeTestProbe(true, (sender: ActorRef) => {
      case SetLanguage(TestSessionId, "es") =>
        languageChanged = true
        NoAutoPilot
    })

    val controller = new SettingsController(new UserAction(sessionManagerProbe.ref), sessionManagerProbe.ref)
    val result = controller.changeLanguage().apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)).withBody(SettingsController.Language("es")))

    assertEquals(Status.OK, status(result))
    assertTrue(languageChanged)
  }

  @Test def getLanguages(): Unit = {
    initializeTestProbe(true, (sender: ActorRef) => {
      case GetLanguages(TestSessionId) =>
        sender ! LanguageInfo(Map("en" -> "English", "es" -> "Español"), "en")
        NoAutoPilot
    })

    val controller = new SettingsController(new UserAction(sessionManagerProbe.ref), sessionManagerProbe.ref)
    val result = controller.listAvailableLanguages.apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)))

    assertEquals(Status.OK, status(result))
    assertEquals(Some(ContentTypes.JSON), contentType(result))
    assertEquals(s"""{"$LanguagesKey":{"en":"English","es":"Español"},"$SelectedLanguageKey":"en"}""", contentAsString(result))
  }
}
