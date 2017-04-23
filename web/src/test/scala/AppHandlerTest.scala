package tela.web

import akka.actor.ActorRef
import akka.testkit.TestActor
import akka.testkit.TestActor.NoAutoPilot
import org.junit.Assert.assertEquals
import org.junit.{Before, Test}
import play.api.http.Status
import play.api.mvc.Cookie
import play.api.test.FakeRequest
import play.api.test.Helpers.{contentAsString, contentType, status}
import tela.web.SessionManager.GetSession

class AppHandlerTest extends SessionManagerClientTestBase {
  private val TestAppName = "TestApp"
  private val TestAppDirectory = "web/src/test/data"

  @Before def initialize(): Unit = {
    doBasicInitialization()
  }

  @Test def notAuthorizedErrorForUserWithoutSession(): Unit = {
    initialiseProbe(None)

    val controller = new AppController(new UserAction(sessionManagerProbe.ref), sessionManagerProbe.ref, TestAppDirectory)
    val result = controller.app(TestAppName).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)))

    assertEquals(Status.UNAUTHORIZED, status(result)(tela.web.timeout))
    assertEquals("", contentAsString(result)(tela.web.timeout))
  }

  @Test def notFoundErrorForUnknownApp(): Unit = {
    initialiseProbe(Some(UserData(TestUsername, DefaultLanguage)))

    val controller = new AppController(new UserAction(sessionManagerProbe.ref), sessionManagerProbe.ref, TestAppDirectory)
    val result = controller.app("NonExistent").apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)))

    assertEquals(Status.NOT_FOUND, status(result)(tela.web.timeout))
    assertEquals("", contentAsString(result)(tela.web.timeout))
  }

  @Test def languageFallsBackToEnglishForMissingStrings(): Unit = {
    initialiseProbe(Some(UserData(TestUsername, "es")))

    val controller = new AppController(new UserAction(sessionManagerProbe.ref), sessionManagerProbe.ref, TestAppDirectory)
    val result = controller.app(TestAppName).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)))

    assertEquals(Status.OK, status(result)(tela.web.timeout))
    assertEquals(TextHtmlContentType, contentType(result)(tela.web.timeout).get) //TODO Constant for text/html
    assertEquals("Spanish English Ambos", contentAsString(result)(tela.web.timeout))
  }

  private def initialiseProbe(userData: Option[UserData]) = {
    sessionManagerProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case GetSession(TestSessionId) =>
            sender ! userData
            NoAutoPilot
        }
    })
  }
}
