package tela.web

import akka.actor.ActorRef
import akka.testkit.TestActor.NoAutoPilot
import org.scalatest.matchers.should.Matchers._
import play.api.http.{ContentTypes, Status}
import play.api.mvc._
import play.api.test.FakeRequest
import tela.web.JSONConversions._
import tela.web.SessionManager.{ChangePassword, GetLanguages, GetSession, SetLanguage}

import scala.concurrent.ExecutionContext.Implicits.global

class SettingsControllerSpec extends SessionManagerClientBaseSpec {
  private def testEnvironment(runTest: TestEnvironment[SettingsController] => Unit): Unit = {
    val components = controllerComponents()
    implicit val bp = bodyParser(components)
    runTest(createTestEnvironment((sessionManager, _) => new SettingsController(new UserAction(sessionManager), sessionManager, components)))
  }

  "changePassword" should "return OK if SessionManager indicates success" in testEnvironment { environment =>
    attemptPasswordChangeAndAssertResponse(environment, resultOfPasswordChange = true, Status.OK)
  }

  it should "return BadRequest if SessionManager indicates failure" in testEnvironment { environment =>
    attemptPasswordChangeAndAssertResponse(environment, resultOfPasswordChange = false, Status.BAD_REQUEST)
  }

  "changeLanguage" should "send request to set language to SessionManager" in testEnvironment { environment =>
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true, (sender: ActorRef) => {
      case _: SetLanguage => NoAutoPilot
    })

    val result = environment.client.changeLanguage().apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionIdAsString)).withBody(SettingsController.Language(SpanishLanguageCode)))

    status(result) should === (Status.OK)
    environment.sessionManagerProbe.expectMsg(GetSession(TestSessionId))
    environment.sessionManagerProbe.expectMsg(SetLanguage(TestSessionId, SpanishLanguageCode))
  }

  "listAvailableLanguages" should "get languages from SessionManager and send them to client as JSON" in testEnvironment { environment =>
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true, (sender: ActorRef) => {
      case GetLanguages(TestSessionId) =>
        sender ! TestLanguageInfo
        NoAutoPilot
    })

    val result = environment.client.listAvailableLanguages.apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionIdAsString)))

    status(result) should === (Status.OK)
    contentType(result) should === (Some(ContentTypes.JSON))
    contentAsString(result) should === (s"""{"$LanguagesKey":{"$DefaultLanguage":"English","$SpanishLanguageCode":"Español"},"$SelectedLanguageKey":"$DefaultLanguage"}""")
  }

  private def attemptPasswordChangeAndAssertResponse(environment: TestEnvironment[SettingsController], resultOfPasswordChange: Boolean, expectedStatus: Int): Unit = {
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true, (sender: ActorRef) => {
      case ChangePassword(TestSessionId, TestPassword, TestNewPassword) =>
        sender ! resultOfPasswordChange
        NoAutoPilot
    })

    val result = environment.client.changePassword.apply(
      FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionIdAsString)).withBody(SettingsController.ChangePasswordRequest(TestPassword, TestNewPassword)))

    status(result) should === (expectedStatus)
  }
}
