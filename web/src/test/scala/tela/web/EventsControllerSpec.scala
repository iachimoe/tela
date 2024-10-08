package tela.web

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Flow, Keep}
import org.apache.pekko.stream.testkit.scaladsl.{TestSink, TestSource}
import org.apache.pekko.testkit.TestActor.{KeepRunning, NoAutoPilot}
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers._
import play.api.http.websocket.{Message, TextMessage}
import play.api.libs.json.Json
import play.api.mvc.{Cookie, Result, Results}
import play.api.test.FakeRequest
import tela.web.JSONConversions.{ActionKey, GetContactListAction}
import tela.web.SessionManager.{GetContactList, GetSession, RegisterWebSocket}

import scala.concurrent.Await

class EventsControllerSpec extends SessionManagerClientBaseSpec with OptionValues {
  private def testEnvironment(runTest: TestEnvironment[EventsController] => Unit): Unit = {
    runTest(createTestEnvironment((sessionManager, actorSystem) => {
      implicit val as: ActorSystem = actorSystem
      import scala.concurrent.ExecutionContext.Implicits.global
      new EventsController(sessionManager)
    }))
  }

  "webSocket" should "return BadRequest if no session cookie is supplied" in testEnvironment { environment =>
    environment.configureTestProbe()
    Await.result(environment.client.webSocket(FakeRequest()), GeneralTimeoutAsDuration) should === (Left(Results.BadRequest))
  }

  it should "return BadRequest if session is not valid" in testEnvironment { environment =>
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = false)
    Await.result(environment.client.webSocket(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionIdAsString))), GeneralTimeoutAsDuration) should === (Left(Results.BadRequest))
  }

  it should "register with SessionManager and forward subsequent input" in testEnvironment { environment =>
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true, _ => {
      case _: RegisterWebSocket => KeepRunning
      case _: GetContactList => NoAutoPilot
    })

    val result: Either[Result, Flow[Message, Message, _]] = Await.result(
      environment.client.webSocket(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionIdAsString))), GeneralTimeoutAsDuration
    )
    val flow: Flow[Message, Message, _] = result.toOption.value
    implicit val m: Materializer = environment.client.materializer
    implicit val as: ActorSystem = environment.sessionManagerProbe.system
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
