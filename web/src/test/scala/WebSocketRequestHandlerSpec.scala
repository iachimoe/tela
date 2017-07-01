package tela.web

import akka.actor.{ActorRef, PoisonPill}
import akka.testkit.TestActor.NoAutoPilot
import akka.testkit.TestActorRef
import play.api.libs.json.{JsValue, Json}
import tela.baseinterfaces.Presence
import tela.web.JSONConversions._
import tela.web.SessionManager._

class WebSocketRequestHandlerSpec extends SessionManagerClientBaseSpec {
  private def testEnvironment(runTest: (TestEnvironment[TestActorRef[WebSocketRequestHandler]]) => Unit): Unit = {
    runTest(createTestEnvironment((sessionManager, actorSystem) => {
      implicit val as = actorSystem
      TestActorRef(new WebSocketRequestHandler(sessionManager, TestSessionId))
    }))
  }

  "setPresence" should "send message to session manager based on presence provided by client" in testEnvironment { environment =>
    environment.configureTestProbe((sender: ActorRef) => {
      case _: SetPresence => NoAutoPilot
    })

    environment.client ! createWebSocketRequestEvent( s"""{"$ActionKey": "$SetPresenceAction", "$DataKey": "${Presence.Available.toString.toLowerCase}"}""")
    environment.sessionManagerProbe.expectMsg(SetPresence(TestSessionId, Presence.Available))
  }

  "getContactList" should "send request for contact list to session manager" in testEnvironment { environment =>
    sendJSONAndAssertMessageSentToSessionManager(environment, GetContactList(TestSessionId), s"""{"$ActionKey": "$GetContactListAction"}""")
  }

  "addContact" should "send AddContact message with given data to session manager" in testEnvironment { environment =>
    sendJSONAndAssertMessageSentToSessionManager(environment, AddContact(TestSessionId, TestContact1), s"""{"$ActionKey": "$AddContactAction", "$DataKey": "$TestContact1"}""")
  }

  "sendCallSignal" should "send SendCallSignal message with given data to session manager" in testEnvironment { environment =>
    sendJSONAndAssertMessageSentToSessionManager(environment, SendCallSignal(TestSessionId, TestContact1, TestCallSignalData),
      s"""{"$ActionKey": "$SendCallSignalAction", "$DataKey": {"$CallSignalRecipientKey": "$TestContact1", "$CallSignalDataKey": $TestCallSignalData}}""")
  }

  "sendChatMessage" should "send SendChatMessage message with given data to session manager" in testEnvironment { environment =>
    sendJSONAndAssertMessageSentToSessionManager(environment, SendChatMessage(TestSessionId, TestContact1, TestChatMessage),
      s"""{"$ActionKey": "$SendChatMessageAction", "$DataKey": {"$ChatMessageRecipientKey": "$TestContact1", "$ChatMessageDataKey": "$TestChatMessage"}}""")
  }

  "WebSocketRequestHandler" should "notify session manager when websocket is closed" in testEnvironment { environment =>
    environment.configureTestProbe((sender: ActorRef) => {
      case _: UnregisterWebSocket => NoAutoPilot
    })

    environment.client ! PoisonPill
    environment.sessionManagerProbe.expectMsg(UnregisterWebSocket(TestSessionId, environment.client))
  }

  private def sendJSONAndAssertMessageSentToSessionManager(environment: TestEnvironment[TestActorRef[WebSocketRequestHandler]], expectedMessageToSessionManager: AnyRef, json: String): Unit = {
    environment.configureTestProbe((sender: ActorRef) => {
      case `expectedMessageToSessionManager` => NoAutoPilot
    })

    environment.client ! createWebSocketRequestEvent(json)
    environment.sessionManagerProbe.expectMsg(expectedMessageToSessionManager)
  }

  private def createWebSocketRequestEvent(content: String): JsValue = {
    Json.parse(content)
  }
}
