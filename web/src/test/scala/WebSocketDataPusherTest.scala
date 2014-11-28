package tela.web

import akka.actor.ActorSystem
import akka.testkit.TestActorRef
import org.junit.Assert._
import org.junit.{Before, Test}
import org.scalatest.junit.AssertionsForJUnit
import org.scalatest.mock.MockitoSugar
import tela.baseinterfaces.{ContactInfo, Presence}
import tela.web.JSONConversions._
import tela.web.WebSocketDataPusher._

class WebSocketDataPusherTest extends AssertionsForJUnit with MockitoSugar {
  private var closedWebSocketIds: Iterable[String] = null
  private var writtenText: String = null
  private var writtenToWebSocketIds: Iterable[String] = null
  private var actorSystem: ActorSystem = null
  private var handler: TestActorRef[WebSocketDataPusher] = null

  private val TestUser1 = "user1@example.com"
  private val TestUser2 = "user2@example.com"
  private val TestMessage = "message"
  private val TestWebSocketIds: Set[String] = Set("asdf", "jkl")

  @Before def initialize(): Unit = {
    actorSystem = ActorSystem("actor")
    closedWebSocketIds = null
    writtenText = null
    writtenToWebSocketIds = null

    implicit val system = actorSystem
    handler = TestActorRef(new WebSocketDataPusher(writeTextToSockets, closeSockets))
  }

  @Test def close(): Unit = {
    handler ! CloseWebSockets(TestWebSocketIds)
    assertSame(TestWebSocketIds, closedWebSocketIds)
  }

  @Test def newContacts(): Unit = {
    handler ! PushContactListInfoToWebSockets(AddContacts(List(ContactInfo(TestUser1, Presence.Available), ContactInfo(TestUser2, Presence.Away))), TestWebSocketIds)
    assertSame(TestWebSocketIds, writtenToWebSocketIds)
    assertEquals( s"""{"$ActionKey":"$AddContactsAction","$DataKey":[""" +
      s"""{"$ContactKey":"$TestUser1","$PresenceKey":"${Presence.Available.toString.toLowerCase}"},""" +
      s"""{"$ContactKey":"$TestUser2","$PresenceKey":"${Presence.Away.toString.toLowerCase}"}]}""", writtenText)
  }

  @Test def presenceUpdate(): Unit = {
    handler ! PushPresenceUpdateToWebSockets(PresenceUpdate(ContactInfo(TestUser1, Presence.Away)), TestWebSocketIds)
    assertSame(TestWebSocketIds, writtenToWebSocketIds)
    assertEquals( s"""{"$ActionKey":"$PresenceUpdateAction","$DataKey":{"$ContactKey":"$TestUser1","$PresenceKey":"${Presence.Away.toString.toLowerCase}"}}""", writtenText)
  }

  @Test def callSignal(): Unit = {
    handler ! PushCallSignalToWebSockets(CallSignalReceipt(TestUser1, TestMessage), TestWebSocketIds)
    assertSame(TestWebSocketIds, writtenToWebSocketIds)
    assertEquals( s"""{"$ActionKey":"$CallSignalReceived","$DataKey":{"$CallSignalSenderKey":"$TestUser1","$CallSignalDataKey":"$TestMessage"}}""", writtenText)
  }

  @Test def chatMessage(): Unit = {
    handler ! PushChatMessageToWebSockets(ChatMessageReceipt(TestUser1, TestMessage), TestWebSocketIds)
    assertSame(TestWebSocketIds, writtenToWebSocketIds)
    assertEquals( s"""{"$ActionKey":"$ChatMessageReceived","$DataKey":{"$ChatMessageSenderKey":"$TestUser1","$ChatMessageDataKey":"$TestMessage"}}""", writtenText)
  }

  private def writeTextToSockets(text: String, webSocketIds: Iterable[String]): Unit = {
    writtenText = text
    writtenToWebSocketIds = webSocketIds
  }

  private def closeSockets(webSocketIds: Iterable[String]): Unit = {
    this.closedWebSocketIds = webSocketIds
  }
}
