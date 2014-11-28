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
  private val TestContactAddress = "foo@bar.net"

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
    sendJSONAndAssertMessageSentToSessionManager(GetContactList(TestSessionId), s"""{"$ActionKey": "$GetContactListAction"}""")
  }

  @Test def addContact(): Unit = {
    sendJSONAndAssertMessageSentToSessionManager(AddContact(TestSessionId, TestContactAddress), s"""{"$ActionKey": "$AddContactAction", "$DataKey": "$TestContactAddress"}""")
  }

  @Test def sendCallSignal(): Unit = {
    val testSignal = """{"type":"offer","sdp":"v=0"}"""
    sendJSONAndAssertMessageSentToSessionManager(SendCallSignal(TestSessionId, TestContactAddress, testSignal),
      s"""{"$ActionKey": "$SendCallSignalAction", "$DataKey": {"$CallSignalRecipientKey": "$TestContactAddress", "$CallSignalDataKey": $testSignal}}""")
  }

  @Test def sendChatMessage(): Unit = {
    val testMessage = "message"
    sendJSONAndAssertMessageSentToSessionManager(SendChatMessage(TestSessionId, TestContactAddress, testMessage),
      s"""{"$ActionKey": "$SendChatMessageAction", "$DataKey": {"$ChatMessageRecipientKey": "$TestContactAddress", "$ChatMessageDataKey": "$testMessage"}}""")
  }

  private def sendJSONAndAssertMessageSentToSessionManager(expectedMessageToSessionManager: AnyRef, json: String): Unit = {
    var sessionManagerReceivedExpectedMessage = false

    initialiseTestActorAndProbe(true, (sender: ActorRef) => {
      case `expectedMessageToSessionManager` =>
        sessionManagerReceivedExpectedMessage = true
        NoAutoPilot
    })

    handler ! createWebSocketRequestEvent(json)
    assertTrue(sessionManagerReceivedExpectedMessage)
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
