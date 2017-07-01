package tela.web

import akka.testkit.TestActor.NoAutoPilot
import org.scalatest.Matchers._
import play.api.http.Status
import play.api.mvc.Cookie
import play.api.test.FakeRequest
import tela.web.SessionManager.GetSession

class AppControllerSpec extends SessionManagerClientBaseSpec {
  private val TestAppName = "TestApp"
  private val TestAppDirectory = "web/src/test/data"

  private def testEnvironment(runTest: (TestEnvironment[AppController]) => Unit): Unit = {
    runTest(createTestEnvironment((sessionManager, _) => new AppController(new UserAction(sessionManager), sessionManager, TestAppDirectory)))
  }

  "app" should "return Unauthorized status code for user without session" in testEnvironment { environment =>
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = false)
    val result = environment.client.app(TestAppName).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)))

    status(result) should === (Status.UNAUTHORIZED)
    contentAsString(result) should === ("")
  }

  it should "return Not Found status code for unknown app" in testEnvironment { environment =>
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true)
    val result = environment.client.app("NonExistent").apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)))

    status(result) should === (Status.NOT_FOUND)
    contentAsString(result) should === ("")
  }

  it should "fall back to English language for missing strings" in testEnvironment { environment =>
    environment.configureTestProbe(sender => {
      case GetSession(TestSessionId) =>
        sender ! Some(UserData(TestUsername, SpanishLanguageCode))
        NoAutoPilot
    })
    val result = environment.client.app(TestAppName).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)))

    status(result) should === (Status.OK)
    contentType(result) should === (Some(TextHtmlContentType))
    contentAsString(result) should === ("Spanish English Ambos")
  }
}
