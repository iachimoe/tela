package tela.web

import org.junit.Assert._
import org.junit.{Before, Test}
import org.scalatest.junit.AssertionsForJUnit
import org.scalatest.mock.MockitoSugar
import tela.baseinterfaces.{DataStoreConnection, XMPPSession}

class SessionInfoTest extends AssertionsForJUnit with MockitoSugar {
  private var xmppSession: XMPPSession = null
  private var dataStoreConnection: DataStoreConnection = null
  private var data: UserData = null

  @Before def initialize(): Unit = {
    xmppSession = mock[XMPPSession]
    dataStoreConnection = mock[DataStoreConnection]
    data = new UserData("user", DefaultLanguage)
  }

  @Test def addWebSocket(): Unit = {
    assertSessionInfoObjectsAreEqual(createSessionInfo(data, Set("aaaaa")), createSessionInfo(data).addWebSocket("aaaaa"))
  }

  @Test def removeWebSocket(): Unit = {
    assertSessionInfoObjectsAreEqual(
      createSessionInfo(data, Set("aaaaa", "ccccc")),
      createSessionInfo(data, Set("aaaaa", "bbbbb", "ccccc")).removeWebSocket("bbbbb"))
  }

  @Test def removeUnknownWebSocket(): Unit = {
    assertSessionInfoObjectsAreEqual(
      createSessionInfo(data, Set("aaaaa", "ccccc")),
      createSessionInfo(data, Set("aaaaa", "ccccc")).removeWebSocket("bbbbb"))
  }

  @Test def changeLanguage(): Unit = {
    assertSessionInfoObjectsAreEqual(
      createSessionInfo(new UserData("user", "es")),
      createSessionInfo(data).changeLanguage("es"))
  }

  private def createSessionInfo(userData: UserData, webSockets: Set[String] = Set()): SessionInfo = {
    new SessionInfo(xmppSession, dataStoreConnection, userData, webSockets)
  }

  private def assertSessionInfoObjectsAreEqual(expected: SessionInfo, actual: SessionInfo): Unit = {
    assertEquals(expected.xmppSession, actual.xmppSession)
    assertEquals(expected.userData, actual.userData)
    assertEquals(expected.webSockets, actual.webSockets)
  }
}
