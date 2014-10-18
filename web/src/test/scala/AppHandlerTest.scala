package tela.web

import akka.actor.ActorRef
import akka.testkit.TestActor.NoAutoPilot
import akka.testkit.{TestActor, TestActorRef}
import io.netty.handler.codec.http.{ClientCookieEncoder, HttpHeaders, HttpMethod}
import org.junit.Assert._
import org.junit.{Before, Test}
import org.mashupbots.socko.events.{HttpRequestEvent, HttpResponseStatus}
import tela.web.SessionManager.GetSession

class AppHandlerTest extends SockoHandlerTestBase {
  private val TestAppName = "TestApp"
  private val TestAppDirectory = "web/src/test/data"

  @Before def initialize(): Unit = {
    doBasicInitialization()
  }

  @Test def notAuthorizedErrorForUserWithoutSession(): Unit = {
    initialiseTestActorAndProbe(TestAppName, None)

    val event = createHttpRequestEvent(HttpMethod.GET, "/apps/" + TestAppName)
    handler ! event

    assertEquals(HttpResponseStatus.UNAUTHORIZED, event.response.status)
    assertResponseBody("")
  }

  @Test def notFoundErrorForUnknownApp(): Unit = {
    initialiseTestActorAndProbe("NonExistent", Some(new UserData(TestUsername, DefaultLanguage)))

    val event = createHttpRequestEvent(HttpMethod.GET, "/apps/" + "NonExistent", Map(HttpHeaders.Names.COOKIE -> ClientCookieEncoder.encode(createCookie(SessionIdCookieName, TestSessionId))))
    handler ! event

    assertEquals(HttpResponseStatus.NOT_FOUND, event.response.status)
    assertResponseBody("")
  }

  @Test def languageFallsBackToEnglishForMissingStrings(): Unit = {
    initialiseTestActorAndProbe(TestAppName, Some(new UserData(TestUsername, "es")))

    val event: HttpRequestEvent = createHttpRequestEvent(HttpMethod.GET, MainPageHandler.WebAppRoot,
      Map(HttpHeaders.Names.COOKIE -> ClientCookieEncoder.encode(createCookie(SessionIdCookieName, TestSessionId))))

    handler ! event

    assertResponseContent(event.response, "Spanish English Ambos")
  }

  //TODO dodgy path test

  private def initialiseTestActorAndProbe(appName: String, userData: Option[UserData]): Unit = {
    handler = TestActorRef(new AppHandler(TestAppDirectory, appName, sessionManagerProbe.ref))

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
