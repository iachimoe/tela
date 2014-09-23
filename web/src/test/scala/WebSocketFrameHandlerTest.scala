package tela.web

import java.util.Date

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestActor.{AutoPilot, KeepRunning, NoAutoPilot}
import akka.testkit.{TestActor, TestActorRef, TestProbe}
import io.netty.channel.{Channel, ChannelHandlerContext}
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


class WebSocketFrameHandlerTest extends SockoHandlerTestBase {
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

    initialiseTestActorAndProbe(true, { case SetPresence(TestSessionId, presence) =>
      presenceSetByWebSocketHandler = presence
      NoAutoPilot
    })

    handler ! createWebSocketRequestEvent( s"""{"$ActionKey": "$SetPresenceAction", "$DataKey": "${Presence.Available.toString.toLowerCase}"}""")

    assertEquals(Presence.Available, presenceSetByWebSocketHandler)
    assertNull(TestWebSocketId, closedWebSocket)
  }

  @Test def getLanguages(): Unit = {
    var getLanguagesCalledOnSessionManager = false

    initialiseTestActorAndProbe(true, { case GetLanguages(TestSessionId) =>
      getLanguagesCalledOnSessionManager = true
      NoAutoPilot
    })

    handler ! createWebSocketRequestEvent( s"""{"$ActionKey": "$GetLanguagesAction"}""")

    assertTrue(getLanguagesCalledOnSessionManager)
  }

  @Test def changePassword(): Unit = {
    var changePasswordCalled = false

    initialiseTestActorAndProbe(true, { case ChangePassword(TestSessionId, "old", "new") =>
      changePasswordCalled = true
      NoAutoPilot
    })

    handler ! createWebSocketRequestEvent( s"""{"$ActionKey": "$ChangePasswordAction", "$DataKey": {"$OldPasswordKey": "old", "$NewPasswordKey": "new"}}""")
    assertTrue(changePasswordCalled)
  }

  @Test def setLanguage(): Unit = {
    var languageChanged = false

    initialiseTestActorAndProbe(true, { case SetLanguage(TestSessionId, "es") =>
      languageChanged = true
      NoAutoPilot
    })

    handler ! createWebSocketRequestEvent( s"""{"$ActionKey": "$SetLanguageAction", "$DataKey": "es"}""")

    assertTrue(languageChanged)
  }

  @Test def getContactList(): Unit = {
    var getContactListCalled = false

    initialiseTestActorAndProbe(true, { case GetContactList(TestSessionId) =>
      getContactListCalled = true
      NoAutoPilot
    })

    handler ! createWebSocketRequestEvent( s"""{"$ActionKey": "$GetContactListAction"}""")
    assertTrue(getContactListCalled)
  }

  @Test def addContact(): Unit = {
    var addContactCalled = false

    initialiseTestActorAndProbe(true, { case AddContact(TestSessionId, "foo@bar.net") =>
      addContactCalled = true
      NoAutoPilot
    })

    handler ! createWebSocketRequestEvent( s"""{"$ActionKey": "$AddContactAction", "$DataKey": "foo@bar.net"}""")
    assertTrue(addContactCalled)
  }

  private def initialiseTestActorAndProbe(shouldReturnUserData: Boolean, expectedCases: PartialFunction[Any, TestActor.AutoPilot]*): Unit = {
    handler = TestActorRef(new WebSocketFrameHandler(sessionManagerProbe.ref, closeWebSocket))

    sessionManagerProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): AutoPilot = {
        val sessionRetriever: PartialFunction[Any, TestActor.AutoPilot] = {
          case GetSession(TestSessionId) =>
            if (shouldReturnUserData) {
              sender ! Some(new UserData(TestUsername, DefaultLanguage))
              KeepRunning
            }
            else {
              sender ! None
              NoAutoPilot
            }
        }

        val allExpectedCases = sessionRetriever :: expectedCases.toList
        allExpectedCases.filter(_.isDefinedAt(msg))(0).apply(msg)
      }
    })
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
