package tela.web

import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import akka.stream.scaladsl.{Flow, Keep}
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.testkit.TestActor.{KeepRunning, NoAutoPilot}
import org.junit.Assert.assertEquals
import org.junit.{Before, Test}
import play.api.http.websocket.{Message, TextMessage}
import play.api.libs.json.Json
import play.api.mvc.{Cookie, Result, Results, WebSocket}
import play.api.test.FakeRequest
import tela.web.JSONConversions.{ActionKey, GetContactListAction}
import tela.web.SessionManager.{GetContactList, GetSession, RegisterWebSocket}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class EventsControllerTest extends SessionManagerClientTestBase {
  private val timeoutAsDuration = Duration(TimeoutDurationInSeconds, TimeUnit.SECONDS)

  @Before def initialize(): Unit = {
    doBasicInitialization()
  }

  @Test def badRequestIfNoSessionCookieSupplied(): Unit = {
    val socket: WebSocket = new EventsController(sessionManagerProbe.ref)(actorSystem, buildMaterializer()).webSocket

    val result: Either[Result, Flow[Message, Message, _]] = Await.result(socket.apply(FakeRequest()), timeoutAsDuration)

    assertEquals(Left(Results.BadRequest), result)
  }

  @Test def badRequestIfSessionIsNotValid(): Unit = {
    initializeTestProbe(shouldReturnUserData = false)

    val socket: WebSocket = new EventsController(sessionManagerProbe.ref)(actorSystem, buildMaterializer()).webSocket

    val result: Either[Result, Flow[Message, Message, _]] = Await.result(
      socket.apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId))), timeoutAsDuration
    )

    assertEquals(Left(Results.BadRequest), result)
  }

  @Test def shouldRegisterWithSessionManagerAndForwardSubsequentInput(): Unit = {
    initializeTestProbe(shouldReturnUserData = true, (sender: ActorRef) => {
      case _: RegisterWebSocket => KeepRunning
      case _: GetContactList => NoAutoPilot
    })

    implicit val mat = buildMaterializer()
    val socket: WebSocket = new EventsController(sessionManagerProbe.ref)(actorSystem, mat).webSocket

    val result: Either[Result, Flow[Message, Message, _]] = Await.result(
      socket.apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId))), timeoutAsDuration
    )
    val flow: Flow[Message, Message, _] = result.right.get
    val (receivesMessagesFromBrowser, sendsMessagesToBrowser) = TestSource.probe[Message].via(flow).toMat(TestSink.probe[Message])(Keep.both).run()

    sessionManagerProbe.expectMsg(GetSession(TestSessionId))
    val webSocketActorRegistration = sessionManagerProbe.expectMsgType[RegisterWebSocket]
    assertEquals(TestSessionId, webSocketActorRegistration.sessionId)

    receivesMessagesFromBrowser.sendNext(TextMessage(s"""{"$ActionKey": "$GetContactListAction"}"""))
    sessionManagerProbe.expectMsg(GetContactList(TestSessionId))

    webSocketActorRegistration.webSocketActorRef ! Json.obj()
    assertEquals(TextMessage("{}"), sendsMessagesToBrowser.requestNext())
  }
}
