package tela.web

import akka.actor.{ActorRef, PoisonPill}
import akka.testkit.TestActor.NoAutoPilot
import akka.testkit.{TestActor, TestActorRef}
import play.api.libs.json.{JsValue, Json}
import org.junit.Assert._
import org.junit.{Before, Test}
import tela.baseinterfaces.Presence
import tela.web.JSONConversions._
import tela.web.SessionManager._


class WebSocketRequestHandlerTest extends SessionManagerClientTestBase {
  private val TestContactAddress = "foo@bar.net"
  private var handler: ActorRef = null

  @Before def initialize(): Unit = {
    doBasicInitialization()
  }

  @Test def setPresence(): Unit = {
    var presenceSetByWebSocketHandler: Presence = null

    initialiseTestActorAndProbe((sender: ActorRef) => {
      case SetPresence(TestSessionId, presence) =>
        presenceSetByWebSocketHandler = presence
        NoAutoPilot
    })

    handler ! createWebSocketRequestEvent( s"""{"$ActionKey": "$SetPresenceAction", "$DataKey": "${Presence.Available.toString.toLowerCase}"}""")

    assertEquals(Presence.Available, presenceSetByWebSocketHandler)
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

  @Test def notifySessionManagerWhenWebSocketIsClosed(): Unit = {
    var unregisteredWebSocketRef: Option[ActorRef] = None

    initialiseTestActorAndProbe((sender: ActorRef) => {
      case UnregisterWebSocket(TestSessionId, ref) =>
        unregisteredWebSocketRef = Some(ref)
        NoAutoPilot
    })
    handler ! PoisonPill
    assertTrue(unregisteredWebSocketRef.contains(handler))
  }

  private def sendJSONAndAssertMessageSentToSessionManager(expectedMessageToSessionManager: AnyRef, json: String): Unit = {
    var sessionManagerReceivedExpectedMessage = false

    initialiseTestActorAndProbe((sender: ActorRef) => {
      case `expectedMessageToSessionManager` =>
        sessionManagerReceivedExpectedMessage = true
        NoAutoPilot
    })

    handler ! createWebSocketRequestEvent(json)
    assertTrue(sessionManagerReceivedExpectedMessage)
  }

  private def initialiseTestActorAndProbe(expectedCases: ((ActorRef) => PartialFunction[Any, TestActor.AutoPilot])*): Unit = {
    handler = TestActorRef(new WebSocketRequestHandler(sessionManagerProbe.ref, TestSessionId))
    initializeTestProbe(shouldReturnUserData = false, expectedCases: _*)
  }

  private def createWebSocketRequestEvent(content: String): JsValue = {
    Json.parse(content)
  }
}
