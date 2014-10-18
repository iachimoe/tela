package tela.web

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.testkit.TestActor.NoAutoPilot
import akka.testkit.{TestActor, TestActorRef, TestProbe}
import org.junit.Assert._
import org.junit.{Before, Test}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.junit.AssertionsForJUnit
import org.scalatest.mock.MockitoSugar
import tela.baseinterfaces._
import tela.web.JSONConversions.{AddContacts, LanguageInfo, PresenceUpdate}
import tela.web.SessionManager._
import tela.web.WebSocketDataPusher._

import scala.concurrent.Await

class SessionManagerTest extends AssertionsForJUnit with MockitoSugar {
  private val TestUsername = "myUser"
  private val TestPassword = "myPass"
  private val TestSessionId = "aaaaaaaaa"
  private val TestXMPPSettings = XMPPSettings("localhost", 5222, "example.com", "disabled")

  private val Languages = LanguageInfo(Map("en" -> "English", "es" -> "Spanish"), DefaultLanguage)

  implicit private var actorSystem: ActorSystem = null
  private var dataPusher: TestProbe = null
  private var xmppSession: XMPPSession = null
  private var xmppLoginName: String = null
  private var xmppPass: String = null
  private var xmppSettings: XMPPSettings = null
  private var dataStoreUsername: String = null
  private var xmppSessionSuppliedToDataStore: XMPPSession = null
  private var dataStoreConnection: DataStoreConnection = null
  private var sessionListener: XMPPSessionListener = null

  @Before def initialize(): Unit = {
    xmppSession = mock[XMPPSession]
    xmppLoginName = null
    xmppPass = null
    dataStoreUsername = null
    xmppSessionSuppliedToDataStore = null
    dataStoreConnection = mock[DataStoreConnection]
    dataPusher = null
    sessionListener = null
    actorSystem = ActorSystem("actor")
  }

  @Test def badLogin(): Unit = {
    val sessionManager = createSessionManager(Left(LoginFailure.InvalidCredentials))

    val result = sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    assertLoginWithTestCredentials(Left(LoginFailure.InvalidCredentials), result)
  }

  @Test def connectionFailed(): Unit = {
    val sessionManager = createSessionManager(Left(LoginFailure.ConnectionFailure))

    val result = sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    assertLoginWithTestCredentials(Left(LoginFailure.ConnectionFailure), result)
  }

  @Test def successfulLogin(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    val result = sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    assertLoginWithTestCredentials(Right(TestSessionId), result)
    assertEquals(TestUsername, dataStoreUsername)
    assertSame(xmppSession, xmppSessionSuppliedToDataStore)
  }

  @Test def requestInvalidSession(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    val result = sendMessageAndGetResponse[Option[String]](sessionManager, GetSession(TestSessionId))

    assertEquals(None, result)
  }

  @Test def requestValidSession(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    val result: Option[UserData] = sendMessageAndGetResponse[Option[UserData]](sessionManager, GetSession(TestSessionId))

    assertEquals(TestUsername, result.get.name)
    assertEquals(DefaultLanguage, result.get.language)
  }

  @Test def logout(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    var closedWebSockets: Set[String] = null

    dataPusher.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case CloseSockets(ids) =>
            closedWebSockets = ids
            NoAutoPilot
        }
    })

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    val webSocketIds: Array[String] = Array("aaaaa", "bbbbb")
    sessionManager ! RegisterWebSocket(TestSessionId, webSocketIds(0))
    sessionManager ! RegisterWebSocket(TestSessionId, webSocketIds(1))

    sessionManager ! Logout(TestSessionId)
    verify(xmppSession).disconnect()
    verify(dataStoreConnection).closeConnection()
    closedWebSockets.zipAll(webSocketIds, null, null).foreach { (s) => assertEquals(s._1, s._2)}

    val result = sendMessageAndGetResponse[Option[String]](sessionManager, GetSession(TestSessionId))

    assertEquals(None, result)
  }

  @Test def setPresence(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    sessionManager ! SetPresence(TestSessionId, Presence.Available)

    verify(xmppSession).setPresence(Presence.Available)
  }

  @Test def registerWebSocket_UnknownSession(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    assertFalse(sendMessageAndGetResponse[Boolean](sessionManager, RegisterWebSocket(TestSessionId, "aaaaa")))
  }

  @Test def registerWebSocket(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    assertTrue(sendMessageAndGetResponse[Boolean](sessionManager, RegisterWebSocket(TestSessionId, "aaaaa")))
  }

  @Test def getLanguages(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    var sentLanguages: LanguageInfo = null
    var idsSentTo: Set[String] = null

    dataPusher.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case SendLanguages(languages, ids) =>
            sentLanguages = languages
            idsSentTo = ids
            NoAutoPilot
        }
    })

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    val webSocketIds: Array[String] = Array("aaaaa", "bbbbb")
    sessionManager ! RegisterWebSocket(TestSessionId, webSocketIds(0))
    sessionManager ! RegisterWebSocket(TestSessionId, webSocketIds(1))

    sessionManager ! GetLanguages(TestSessionId)

    assertEquals(Languages, sentLanguages)
    idsSentTo.zipAll(webSocketIds, null, null).foreach { (s) => assertEquals(s._1, s._2)}
  }

  @Test def getLanguages_NonDefaultLanguage(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    var sentLanguages: LanguageInfo = null
    var idsSentTo: Set[String] = null

    dataPusher.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case SendLanguages(languages, ids) =>
            sentLanguages = languages
            idsSentTo = ids
            NoAutoPilot
        }
    })

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, "es"))

    val webSocketIds: Array[String] = Array("aaaaa", "bbbbb")
    sessionManager ! RegisterWebSocket(TestSessionId, webSocketIds(0))
    sessionManager ! RegisterWebSocket(TestSessionId, webSocketIds(1))

    sessionManager ! GetLanguages(TestSessionId)

    assertEquals(new LanguageInfo(Languages.languages, "es"), sentLanguages)
    idsSentTo.zipAll(webSocketIds, null, null).foreach { (s) => assertEquals(s._1, s._2)}
  }

  @Test def deregisterWebSocket(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    var closedWebSockets: Set[String] = null

    dataPusher.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case CloseSockets(ids) =>
            closedWebSockets = ids
            NoAutoPilot
        }
    })

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    val webSocketIds: Array[String] = Array("aaaaa", "bbbbb", "ccccc")
    sessionManager ! RegisterWebSocket(TestSessionId, webSocketIds(0))
    sessionManager ! RegisterWebSocket(TestSessionId, webSocketIds(1))
    sessionManager ! RegisterWebSocket(TestSessionId, webSocketIds(2))

    sessionManager ! UnregisterWebSocket(TestSessionId, webSocketIds(1))

    sessionManager ! Logout(TestSessionId)
    verify(xmppSession).disconnect()
    closedWebSockets.zipAll(Array("aaaaa", "ccccc"), null, null).foreach { (s) => assertEquals(s._1, s._2)}

    //TODO Testing deregister for unknown session. Should probably be doing this as separate test...
    sessionManager.receive(UnregisterWebSocket("asdf", "qwer"))
  }

  @Test def changePassword(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    var successfulPasswordChangeNotificationReceived = false
    val webSocketIds: Set[String] = Set("aaaaa", "bbbbb")

    dataPusher.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case SendChangePasswordResult(true, `webSocketIds`) =>
            successfulPasswordChangeNotificationReceived = true
            NoAutoPilot
        }
    })

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    webSocketIds.foreach(sessionManager ! RegisterWebSocket(TestSessionId, _))

    when(xmppSession.changePassword("oldPassword", "newPassword")).thenReturn(true)
    sessionManager ! ChangePassword(TestSessionId, "oldPassword", "newPassword")

    assertTrue(successfulPasswordChangeNotificationReceived)
  }

  @Test def changePassword_Fail(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    var failedPasswordChangeNotificationReceived = false
    val webSocketIds: Set[String] = Set("aaaaa", "bbbbb")

    dataPusher.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case SendChangePasswordResult(false, `webSocketIds`) =>
            failedPasswordChangeNotificationReceived = true
            NoAutoPilot
        }
    })

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    webSocketIds.foreach(sessionManager ! RegisterWebSocket(TestSessionId, _))

    when(xmppSession.changePassword("oldPassword", "newPassword")).thenReturn(false)
    sessionManager ! ChangePassword(TestSessionId, "oldPassword", "newPassword")

    assertTrue(failedPasswordChangeNotificationReceived)
  }

  @Test def setPreferredLanguage(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, "es"))

    assertEquals("es", sendMessageAndGetResponse[Option[UserData]](sessionManager, GetSession(TestSessionId)).get.language)

    sessionManager ! SetLanguage(TestSessionId, DefaultLanguage)

    assertEquals(DefaultLanguage, sendMessageAndGetResponse[Option[UserData]](sessionManager, GetSession(TestSessionId)).get.language)
  }

  @Test def getContactList(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    var addContactsMessageSent = false
    val webSocketIds: Set[String] = Set("aaaaa", "bbbbb")

    dataPusher.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case SendContactListInfo(AddContacts(List(ContactInfo("foo@bar.net", Presence.Available), ContactInfo("bar@foo.net", Presence.Away))), `webSocketIds`) =>
            addContactsMessageSent = true
            NoAutoPilot
        }
    })

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    webSocketIds.foreach(sessionManager ! RegisterWebSocket(TestSessionId, _))

    when(xmppSession.getContactList).then(new Answer[Unit] {
      override def answer(invocation: InvocationOnMock): Unit = {
        sessionListener.contactsAdded(List(ContactInfo("foo@bar.net", Presence.Available), ContactInfo("bar@foo.net", Presence.Away)))
      }
    })

    sessionManager ! GetContactList(TestSessionId)

    assertTrue(addContactsMessageSent)
  }

  @Test def presenceUpdated(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    var presenceUpdateSent = false
    val webSocketIds: Set[String] = Set("aaaaa", "bbbbb")

    dataPusher.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case SendPresenceUpdate(PresenceUpdate(ContactInfo("foo@bar.net", Presence.Away)), `webSocketIds`) =>
            presenceUpdateSent = true
            NoAutoPilot
        }
    })

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    webSocketIds.foreach(sessionManager ! RegisterWebSocket(TestSessionId, _))

    sessionListener.presenceChanged(ContactInfo("foo@bar.net", Presence.Away))

    assertTrue(presenceUpdateSent)
  }

  @Test def addContact(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    sessionManager ! AddContact(TestSessionId, "foo@bar.net")

    verify(xmppSession).addContact("foo@bar.net")
  }

  @Test def publishData(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    sessionManager ! PublishData(TestSessionId, "[]", "http://uri")

    verify(dataStoreConnection).insertJSON("[]")
    verify(dataStoreConnection).publish("http://uri")
  }

  @Test def retrieveData(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    when(dataStoreConnection.retrieveJSON("http://myData")).thenReturn("[]")

    val result = sendMessageAndGetResponse[String](sessionManager, RetrieveData(TestSessionId, "http://myData"))

    assertEquals("[]", result)
  }

  @Test def retrieveDataFromOtherUser(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    when(dataStoreConnection.retrievePublishedDataAsJSON("otherUser", "http://myData")).thenReturn("[]")

    val result = sendMessageAndGetResponse[String](sessionManager, RetrievePublishedData(TestSessionId, "otherUser", "http://myData"))

    assertEquals("[]", result)
  }

  private def createSessionManager(result: Either[LoginFailure, XMPPSession]): TestActorRef[SessionManager] = {
    dataPusher = TestProbe()

    TestActorRef(new SessionManager((user: String, pass: String, settings: XMPPSettings, sessionListener: XMPPSessionListener) => {
      xmppLoginName = user
      xmppPass = pass
      xmppSettings = settings
      this.sessionListener = sessionListener
      result
    }, (user: String, xmppSession: XMPPSession) => {
      dataStoreUsername = user
      xmppSessionSuppliedToDataStore = xmppSession
      dataStoreConnection
    },
      Languages.languages, TestXMPPSettings, dataPusher.ref, () => TestSessionId))
  }

  private def sendMessageAndGetResponse[ResponseType](actor: ActorRef, message: Any): ResponseType = {
    implicit val timeout = ActorTimeout
    Await.result(actor ? message, timeout.duration).asInstanceOf[ResponseType]
  }

  private def assertLoginWithTestCredentials(expectedResult: Either[LoginFailure, String], actualResult: Either[LoginFailure, String]) {
    assertEquals(expectedResult, actualResult)
    assertEquals(TestUsername, xmppLoginName)
    assertEquals(TestPassword, xmppPass)
    assertEquals(TestXMPPSettings, xmppSettings)
  }
}
