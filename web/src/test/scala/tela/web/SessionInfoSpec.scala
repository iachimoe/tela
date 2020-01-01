package tela.web

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestProbe
import org.scalatest.matchers.should.Matchers._
import tela.baseinterfaces.{DataStoreConnection, XMPPSession}

class SessionInfoSpec extends WebBaseSpec {
  private val TestUserData = UserData(TestUsername, DefaultLanguage)

  private class TestEnvironment(val xmppSession: XMPPSession, val dataStoreConnection: DataStoreConnection, val actorSystem: ActorSystem)

  private def testEnvironment(runTest: TestEnvironment => Unit) = {
    runTest(new TestEnvironment(mock[XMPPSession], mock[DataStoreConnection], ActorSystem("actor")))
  }

  "addWebSocket" should "add a websocket to the session" in testEnvironment { environment =>
    implicit val actorSystem = environment.actorSystem
    val testWebSocket: ActorRef = TestProbe().ref

    val sessionWithoutWebSockets = new SessionInfo(environment.xmppSession, environment.dataStoreConnection, TestUserData)
    val sessionWithWebSocket = new SessionInfo(environment.xmppSession, environment.dataStoreConnection, TestUserData, Set(testWebSocket))
    assertSessionInfoObjectsAreEqual(sessionWithoutWebSockets.addWebSocket(testWebSocket), sessionWithWebSocket)
  }

  "removeWebSocket" should "remove the given websocket from the session" in testEnvironment { environment =>
    implicit val actorSystem = environment.actorSystem
    val testWebSocket1: ActorRef = TestProbe().ref
    val testWebSocket2: ActorRef = TestProbe().ref
    val testWebSocket3: ActorRef = TestProbe().ref

    val sessionWithThreeWebSockets = new SessionInfo(environment.xmppSession, environment.dataStoreConnection, TestUserData, Set(testWebSocket1, testWebSocket2, testWebSocket3))
    val sessionWithoutWebSocket2 = new SessionInfo(environment.xmppSession, environment.dataStoreConnection, TestUserData, Set(testWebSocket1, testWebSocket3))
    assertSessionInfoObjectsAreEqual(sessionWithThreeWebSockets.removeWebSocket(testWebSocket2), sessionWithoutWebSocket2)
  }

  it should "do nothing if asked to remove a websocket that is not registered with the session" in testEnvironment { environment =>
    implicit val actorSystem = environment.actorSystem
    val testWebSocket1: ActorRef = TestProbe().ref
    val testWebSocket2: ActorRef = TestProbe().ref
    val testWebSocket3: ActorRef = TestProbe().ref

    val sessionWithoutWebSocket2 = new SessionInfo(environment.xmppSession, environment.dataStoreConnection, TestUserData, Set(testWebSocket1, testWebSocket3))
    assertSessionInfoObjectsAreEqual(sessionWithoutWebSocket2.removeWebSocket(testWebSocket2), sessionWithoutWebSocket2)

  }

  "changeLanguage" should "set the user's preferred language to the given value" in testEnvironment { environment =>
    val sessionWithDefaultUserData = new SessionInfo(environment.xmppSession, environment.dataStoreConnection, TestUserData)
    val sessionWithSpanishLanguage = new SessionInfo(environment.xmppSession, environment.dataStoreConnection, UserData(TestUsername, SpanishLanguageCode))
    assertSessionInfoObjectsAreEqual(sessionWithDefaultUserData.changeLanguage(SpanishLanguageCode), sessionWithSpanishLanguage)
  }

  private def assertSessionInfoObjectsAreEqual(expected: SessionInfo, actual: SessionInfo): Unit = {
    actual.xmppSession should === (expected.xmppSession)
    actual.dataStoreConnection should === (expected.dataStoreConnection)
    actual.userData should === (expected.userData)
    actual.webSockets should === (expected.webSockets)
  }
}
