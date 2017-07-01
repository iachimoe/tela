package tela.web

import akka.actor.ActorRef
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Keep}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.testkit.TestActor.{KeepRunning, NoAutoPilot}
import org.scalatest.Matchers._
import play.api.http.websocket.{Message, TextMessage}
import play.api.libs.json.Json
import play.api.mvc.{Cookie, Result, Results}
import play.api.test.FakeRequest
import tela.web.JSONConversions.{ActionKey, GetContactListAction}
import tela.web.SessionManager.{GetContactList, GetSession, RegisterWebSocket}

import scala.concurrent.Await

class EventsControllerSpec extends SessionManagerClientBaseSpec {
  private def testEnvironment(runTest: (TestEnvironment[EventsController], Materializer) => Unit): Unit = {
    val materializer = buildMaterializer()
    val environment = createTestEnvironment((sessionManager, actorSystem) => new EventsController(sessionManager)(actorSystem, materializer))
    runTest(environment, materializer)
  }

  "webSocket" should "return BadRequest if no session cookie is supplied" in testEnvironment { (environment, _) =>
    environment.configureTestProbe()
    Await.result(environment.client.webSocket(FakeRequest()), GeneralTimeoutAsDuration) should === (Left(Results.BadRequest))
  }

  it should "return BadRequest if session is not valid" in testEnvironment { (environment, _) =>
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = false)
    Await.result(environment.client.webSocket(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId))), GeneralTimeoutAsDuration) should === (Left(Results.BadRequest))
  }

  it should "register with SessionManager and forward subsequent input" in testEnvironment { (environment, materializer) =>
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true, (sender: ActorRef) => {
      case _: RegisterWebSocket => KeepRunning
      case _: GetContactList => NoAutoPilot
    })

    val result: Either[Result, Flow[Message, Message, _]] = Await.result(
      environment.client.webSocket(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId))), GeneralTimeoutAsDuration
    )
    val flow: Flow[Message, Message, _] = result.right.get
    implicit val m = materializer
    implicit val as = environment.sessionManagerProbe.system
    val (receivesMessagesFromBrowser, sendsMessagesToBrowser) = TestSource.probe[Message].via(flow).toMat(TestSink.probe[Message])(Keep.both).run()

    environment.sessionManagerProbe.expectMsg(GetSession(TestSessionId))
    val webSocketActorRegistration = environment.sessionManagerProbe.expectMsgType[RegisterWebSocket]
    webSocketActorRegistration.sessionId should === (TestSessionId)

    receivesMessagesFromBrowser.sendNext(TextMessage(s"""{"$ActionKey": "$GetContactListAction"}"""))
    environment.sessionManagerProbe.expectMsg(GetContactList(TestSessionId))

    webSocketActorRegistration.webSocketActorRef ! Json.obj()
    sendsMessagesToBrowser.requestNext() should === (TextMessage("{}"))
  }
}
