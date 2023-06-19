package tela.web

import java.nio.file.Paths
import akka.testkit.TestActor.NoAutoPilot
import org.scalatest.matchers.should.Matchers._
import play.api.http.Status
import play.api.mvc.Cookie
import play.api.test.FakeRequest
import tela.web.SessionManager.GetSession

import scala.concurrent.ExecutionContext.Implicits.global

class AppControllerSpec extends SessionManagerClientBaseSpec {
  private val TestAppName = "TestApp"
  private val TestAppDirectory = Paths.get("web/src/test/data")

  private def testEnvironment(runTest: TestEnvironment[AppController] => Unit): Unit = {
    val components = controllerComponents()
    implicit val bp = bodyParser(components)
    runTest(createTestEnvironment((sessionManager, _) =>
      new AppController(new UserAction(sessionManager), TestAppDirectory,
        components, createMockAssetsFinder())))
  }

  "app" should "return Unauthorized status code for user without session" in testEnvironment { environment =>
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = false)
    val result = environment.client.app(TestAppName).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionIdAsString)))

    status(result) should === (Status.UNAUTHORIZED)
    contentAsString(result) should === ("")
  }

  it should "return Not Found status code for unknown app" in testEnvironment { environment =>
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true)
    val result = environment.client.app("NonExistent").apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionIdAsString)))

    status(result) should === (Status.NOT_FOUND)
    contentAsString(result) should === ("")
  }

  it should "fall back to English language for missing strings and include javascript dependency info" in testEnvironment { environment =>
    environment.configureTestProbe(sender => {
      case GetSession(TestSessionId) =>
        sender ! Some(UserData(TestUsername, SpanishLanguageCode))
        NoAutoPilot
    })
    val result = environment.client.app(TestAppName).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionIdAsString)))

    status(result) should === (Status.OK)
    contentType(result) should === (Some(TextHtmlContentType))
    contentAsString(result) should === (s"Spanish English Ambos $BootstrapTestAssetPath $FontAwesomeTestAssetPath $TextEditorTestAssetPath\n")
  }
}
