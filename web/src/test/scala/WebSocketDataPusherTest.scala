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
    handler ! CloseSockets(TestWebSocketIds)
    assertSame(TestWebSocketIds, closedWebSocketIds)
  }

  @Test def sendLanguages(): Unit = {
    handler ! SendLanguages(LanguageInfo(Map("en" -> "English", "es" -> "Español"), "en"), TestWebSocketIds)
    assertSame(TestWebSocketIds, writtenToWebSocketIds)
    assertEquals( s"""{"$ActionKey":"$SetLanguagesAction","$DataKey":{"$LanguagesKey":{"en":"English","es":"Español"},"$SelectedLanguageKey":"en"}}""", writtenText)
  }

  @Test def sendChangePasswordResult_True(): Unit = {
    handler ! SendChangePasswordResult(true, TestWebSocketIds)
    assertSame(TestWebSocketIds, writtenToWebSocketIds)
    assertEquals( s"""{"$ActionKey":"$ChangePasswordSucceeded"}""", writtenText)
  }

  @Test def sendChangePasswordResult_False(): Unit = {
    handler ! SendChangePasswordResult(false, TestWebSocketIds)
    assertSame(TestWebSocketIds, writtenToWebSocketIds)
    assertEquals( s"""{"$ActionKey":"$ChangePasswordFailed"}""", writtenText)
  }

  @Test def sendNewContacts(): Unit = {
    handler ! SendContactListInfo(AddContacts(List(ContactInfo(TestUser1, Presence.Available), ContactInfo(TestUser2, Presence.Away))), TestWebSocketIds)
    assertSame(TestWebSocketIds, writtenToWebSocketIds)
    assertEquals( s"""{"$ActionKey":"$AddContactsAction","$DataKey":[""" +
      s"""{"$ContactKey":"$TestUser1","$PresenceKey":"${Presence.Available.toString.toLowerCase}"},""" +
      s"""{"$ContactKey":"$TestUser2","$PresenceKey":"${Presence.Away.toString.toLowerCase}"}]}""", writtenText)
  }

  @Test def presenceUpdate(): Unit = {
    handler ! SendPresenceUpdate(PresenceUpdate(ContactInfo(TestUser1, Presence.Away)), TestWebSocketIds)
    assertSame(TestWebSocketIds, writtenToWebSocketIds)
    assertEquals( s"""{"$ActionKey":"$PresenceUpdateAction","$DataKey":{"$ContactKey":"$TestUser1","$PresenceKey":"${Presence.Away.toString.toLowerCase}"}}""", writtenText)
  }

  private def writeTextToSockets(text: String, webSocketIds: Iterable[String]): Unit = {
    writtenText = text
    writtenToWebSocketIds = webSocketIds
  }

  private def closeSockets(webSocketIds: Iterable[String]): Unit = {
    this.closedWebSocketIds = webSocketIds
  }
}
