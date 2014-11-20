package tela.web

import java.util.Date

import akka.actor.ActorRef
import akka.testkit.TestActor.NoAutoPilot
import akka.testkit.{TestActor, TestActorRef}
import io.netty.channel.Channel
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.util.DefaultAttributeMap
import org.junit.Assert._
import org.junit.{Before, Test}
import org.mashupbots.socko.events.{CurrentHttpRequestMessage, InitialHttpRequestMessage, WebSocketEventConfig, WebSocketFrameEvent}
import org.mockito.Mockito._
import tela.baseinterfaces.Presence
import tela.web.JSONConversions._
import tela.web.SessionManager._


class WebSocketRequestHandlerTest extends SockoHandlerTestBase {
  private val TestWebSocketId = "wsid"

  private var closedWebSocket: String = null

  private var channel: Channel = null
  private val map = new DefaultAttributeMap()

  @Before def initialize(): Unit = {
    doBasicInitialization()

    closedWebSocket = null

    channel = mock[Channel]
    map.attr(WebSocketEventConfig.webSocketIdKey).set(TestWebSocketId)
    when(channel.attr(WebSocketEventConfig.webSocketIdKey)).thenReturn(map.attr(WebSocketEventConfig.webSocketIdKey))
    when(channelHandlerContext.channel()).thenReturn(channel)
  }

  @Test def closeWebSocketIfSessionIdIsNotValid(): Unit = {
    initialiseTestActorAndProbe(false)

    handler ! createWebSocketRequestEvent("")

    assertEquals(TestWebSocketId, closedWebSocket)
  }

  @Test def setPresence(): Unit = {
    var presenceSetByWebSocketHandler: Presence = null

    initialiseTestActorAndProbe(true, (sender: ActorRef) => {
      case SetPresence(TestSessionId, presence) =>
        presenceSetByWebSocketHandler = presence
        NoAutoPilot
    })

    handler ! createWebSocketRequestEvent( s"""{"$ActionKey": "$SetPresenceAction", "$DataKey": "${Presence.Available.toString.toLowerCase}"}""")

    assertEquals(Presence.Available, presenceSetByWebSocketHandler)
    assertNull(TestWebSocketId, closedWebSocket)
  }

  @Test def getContactList(): Unit = {
    var getContactListCalled = false

    initialiseTestActorAndProbe(true, (sender: ActorRef) => {
      case GetContactList(TestSessionId) =>
        getContactListCalled = true
        NoAutoPilot
    })

    handler ! createWebSocketRequestEvent( s"""{"$ActionKey": "$GetContactListAction"}""")
    assertTrue(getContactListCalled)
  }

  @Test def addContact(): Unit = {
    var addContactCalled = false

    initialiseTestActorAndProbe(true, (sender: ActorRef) => {
      case AddContact(TestSessionId, "foo@bar.net") =>
        addContactCalled = true
        NoAutoPilot
    })

    handler ! createWebSocketRequestEvent( s"""{"$ActionKey": "$AddContactAction", "$DataKey": "foo@bar.net"}""")
    assertTrue(addContactCalled)
  }

  @Test def sendCallSignal(): Unit = {
    val testSignal = """{"type":"offer","sdp":"v=0"}"""

    var sendCallSignalCalled = false

    initialiseTestActorAndProbe(true, (sender: ActorRef) => {
      case SendCallSignal(TestSessionId, "foo@bar.net", `testSignal`) =>
        sendCallSignalCalled = true
        NoAutoPilot
    })

    handler ! createWebSocketRequestEvent( s"""{"$ActionKey": "$SendCallSignalAction", "$DataKey": {"$CallSignalRecipientKey": "foo@bar.net", "$CallSignalDataKey": $testSignal}}""")
    assertTrue(sendCallSignalCalled)
  }

  private def initialiseTestActorAndProbe(shouldReturnUserData: Boolean, expectedCases: ((ActorRef) => PartialFunction[Any, TestActor.AutoPilot])*): Unit = {
    handler = TestActorRef(new WebSocketRequestHandler(sessionManagerProbe.ref, closeWebSocket))
    initializeTestProbe(shouldReturnUserData, expectedCases: _*)
  }

  private def closeWebSocket(webSocketId: String): Unit = {
    closedWebSocket = webSocketId
  }

  private def createWebSocketRequestEvent(content: String): WebSocketFrameEvent = {
    val config = new WebSocketEventConfig("", None)
    val frame = new TextWebSocketFrame(content)
    val message = new InitialHttpRequestMessage(new CurrentHttpRequestMessage(
      createHttpRequest(HttpMethod.GET, MainPageHandler.WebAppRoot,
        Map(HttpHeaders.Names.COOKIE -> ClientCookieEncoder.encode(createCookie(SessionIdCookieName, TestSessionId))))), new Date())
    new WebSocketFrameEvent(channelHandlerContext, message, frame, config)
  }
}
