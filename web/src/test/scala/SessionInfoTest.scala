package tela.web

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestProbe
import org.junit.Assert._
import org.junit.{Before, Test}
import org.scalatest.junit.AssertionsForJUnit
import org.scalatest.mock.MockitoSugar
import tela.baseinterfaces.{DataStoreConnection, XMPPSession}

class SessionInfoTest extends AssertionsForJUnit with MockitoSugar {
  private var xmppSession: XMPPSession = null
  private var dataStoreConnection: DataStoreConnection = null
  private var data: UserData = null
  private implicit var actorSystem: ActorSystem = null

  @Before def initialize(): Unit = {
    xmppSession = mock[XMPPSession]
    dataStoreConnection = mock[DataStoreConnection]
    data = UserData("user", DefaultLanguage)
    actorSystem = ActorSystem("actor")
  }

  @Test def addWebSocket(): Unit = {
    val testWebSocket: ActorRef = TestProbe().ref

    assertSessionInfoObjectsAreEqual(createSessionInfo(data, Set(testWebSocket)), createSessionInfo(data).addWebSocket(testWebSocket))
  }

  @Test def removeWebSocket(): Unit = {
    val testWebSocket1: ActorRef = TestProbe().ref
    val testWebSocket2: ActorRef = TestProbe().ref
    val testWebSocket3: ActorRef = TestProbe().ref

    assertSessionInfoObjectsAreEqual(
      createSessionInfo(data, Set(testWebSocket1, testWebSocket3)),
      createSessionInfo(data, Set(testWebSocket1, testWebSocket2, testWebSocket3)).removeWebSocket(testWebSocket2))
  }

  @Test def removeUnknownWebSocket(): Unit = {
    val testWebSocket1: ActorRef = TestProbe().ref
    val testWebSocket2: ActorRef = TestProbe().ref
    val testWebSocket3: ActorRef = TestProbe().ref

    assertSessionInfoObjectsAreEqual(
      createSessionInfo(data, Set(testWebSocket1, testWebSocket3)),
      createSessionInfo(data, Set(testWebSocket1, testWebSocket3)).removeWebSocket(testWebSocket2))
  }

  @Test def changeLanguage(): Unit = {
    assertSessionInfoObjectsAreEqual(
      createSessionInfo(UserData("user", "es")),
      createSessionInfo(data).changeLanguage("es"))
  }

  private def createSessionInfo(userData: UserData, webSockets: Set[ActorRef] = Set()): SessionInfo = {
    new SessionInfo(xmppSession, dataStoreConnection, userData, webSockets)
  }

  private def assertSessionInfoObjectsAreEqual(expected: SessionInfo, actual: SessionInfo): Unit = {
    assertEquals(expected.xmppSession, actual.xmppSession)
    assertEquals(expected.userData, actual.userData)
    assertEquals(expected.webSockets, actual.webSockets)
  }
}
