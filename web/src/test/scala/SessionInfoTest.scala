package tela.web

import org.junit.{Before, Test}
import org.junit.Assert._
import org.scalatest.junit.AssertionsForJUnit
import org.scalatest.mock.MockitoSugar
import tela.baseinterfaces.XMPPSession

class SessionInfoTest extends AssertionsForJUnit with MockitoSugar {
  private var xmppSession: XMPPSession = null
  private var data: UserData = null

  @Before def initialize(): Unit = {
    xmppSession = mock[XMPPSession]
    data = new UserData("user", DefaultLanguage)
  }

  @Test def addWebSocket(): Unit =
  {
    assertSessionInfoObjectsAreEqual(new SessionInfo(xmppSession, data, Set("aaaaa")), new SessionInfo(xmppSession, data).addWebSocket("aaaaa"))
  }

  @Test def removeWebSocket(): Unit = {
    assertSessionInfoObjectsAreEqual(
      new SessionInfo(xmppSession, data, Set("aaaaa", "ccccc")),
      new SessionInfo(xmppSession, data, Set("aaaaa", "bbbbb", "ccccc")).removeWebSocket("bbbbb"))
  }

  @Test def removeUnknownWebSocket(): Unit =
  {
    assertSessionInfoObjectsAreEqual(
      new SessionInfo(xmppSession, data, Set("aaaaa", "ccccc")),
      new SessionInfo(xmppSession, data, Set("aaaaa", "ccccc")).removeWebSocket("bbbbb"))
  }

  @Test def changeLanguage(): Unit =
  {
    assertSessionInfoObjectsAreEqual(
      new SessionInfo(xmppSession, new UserData("user", "es")),
      new SessionInfo(xmppSession, data).changeLanguage("es"))
  }

  private def assertSessionInfoObjectsAreEqual(expected: SessionInfo, actual: SessionInfo): Unit = {
    assertEquals(expected.xmppSession, actual.xmppSession)
    assertEquals(expected.userData, actual.userData)
    assertEquals(expected.webSockets, actual.webSockets)
  }
}
