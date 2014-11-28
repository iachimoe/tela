package tela.web

import akka.actor.{ActorRef, ActorSystem}
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
import tela.web.JSONConversions._
import tela.web.SessionManager._
import tela.web.WebSocketDataPusher._

class SessionManagerTest extends AssertionsForJUnit with MockitoSugar {
  private val TestUsername = "myUser"
  private val TestPassword = "myPass"
  private val TestSessionId = "aaaaaaaaa"
  private val TestXMPPSettings = XMPPSettings("localhost", 5222, "example.com", "disabled")
  private val TestWebSocketIds = Set("aaaaa", "bbbbb");

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
    val result: Option[UserData] = loginAndSendMessageExpectingResponse[Option[UserData]](GetSession(TestSessionId))
    assertEquals(TestUsername, result.get.name)
    assertEquals(DefaultLanguage, result.get.language)
  }

  @Test def logout(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    var webSocketsClosed = false

    dataPusher.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case CloseWebSockets(`TestWebSocketIds`) =>
            webSocketsClosed = true
            NoAutoPilot
        }
    })

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))
    TestWebSocketIds.foreach(sessionManager ! RegisterWebSocket(TestSessionId, _))

    sessionManager ! Logout(TestSessionId)
    verify(xmppSession).disconnect()
    verify(dataStoreConnection).closeConnection()

    assertTrue(webSocketsClosed)

    val result = sendMessageAndGetResponse[Option[String]](sessionManager, GetSession(TestSessionId))

    assertEquals(None, result)
  }

  @Test def setPresence(): Unit = {
    loginAndSendMessage(SetPresence(TestSessionId, Presence.Available))
    verify(xmppSession).setPresence(Presence.Available)
  }

  @Test def registerWebSocket_UnknownSession(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))
    assertFalse(sendMessageAndGetResponse[Boolean](sessionManager, RegisterWebSocket(TestSessionId, "aaaaa")))
  }

  @Test def registerWebSocket(): Unit = {
    assertTrue(loginAndSendMessageExpectingResponse[Boolean](RegisterWebSocket(TestSessionId, "aaaaa")))
  }

  @Test def getLanguages(): Unit = {
    assertEquals(Languages, loginAndSendMessageExpectingResponse[LanguageInfo](GetLanguages(TestSessionId)))
  }

  @Test def getLanguages_NonDefaultLanguage(): Unit = {
    assertEquals(new LanguageInfo(Languages.languages, "es"), loginAndSendMessageExpectingResponse[LanguageInfo](GetLanguages(TestSessionId), "es"))
  }

  @Test def deregisterWebSocket(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    var closedWebSockets: Set[String] = null

    dataPusher.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case CloseWebSockets(ids) =>
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
    when(xmppSession.changePassword("oldPassword", "newPassword")).thenReturn(true)
    assertTrue(loginAndSendMessageExpectingResponse(ChangePassword(TestSessionId, "oldPassword", "newPassword")))
  }

  @Test def changePassword_Fail(): Unit = {
    when(xmppSession.changePassword("oldPassword", "newPassword")).thenReturn(false)
    assertFalse(loginAndSendMessageExpectingResponse(ChangePassword(TestSessionId, "oldPassword", "newPassword")))
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

    dataPusher.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case PushContactListInfoToWebSockets(AddContacts(List(ContactInfo("foo@bar.net", Presence.Available), ContactInfo("bar@foo.net", Presence.Away))), `TestWebSocketIds`) =>
            addContactsMessageSent = true
            NoAutoPilot
        }
    })

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    TestWebSocketIds.foreach(sessionManager ! RegisterWebSocket(TestSessionId, _))

    when(xmppSession.getContactList).then(new Answer[Unit] {
      override def answer(invocation: InvocationOnMock): Unit = {
        sessionListener.contactsAdded(List(ContactInfo("foo@bar.net", Presence.Available), ContactInfo("bar@foo.net", Presence.Away)))
      }
    })

    sessionManager ! GetContactList(TestSessionId)

    assertTrue(addContactsMessageSent)
  }

  @Test def presenceUpdated(): Unit = {
    loginAndAssertThatWebsocketsAreNotifiedOfEvent(PushPresenceUpdateToWebSockets(PresenceUpdate(ContactInfo("foo@bar.net", Presence.Away)), TestWebSocketIds),
      () => sessionListener.presenceChanged(ContactInfo("foo@bar.net", Presence.Away)))
  }

  @Test def addContact(): Unit = {
    loginAndSendMessage(AddContact(TestSessionId, "foo@bar.net"))
    verify(xmppSession).addContact("foo@bar.net")
  }

  @Test def publishData(): Unit = {
    loginAndSendMessage(PublishData(TestSessionId, "[]", "http://uri"))
    verify(dataStoreConnection).insertJSON("[]")
    verify(dataStoreConnection).publish("http://uri")
  }

  @Test def retrieveData(): Unit = {
    when(dataStoreConnection.retrieveJSON("http://myData")).thenReturn("[]")
    assertEquals("[]", loginAndSendMessageExpectingResponse[String](RetrieveData(TestSessionId, "http://myData")))
  }

  @Test def retrieveDataFromOtherUser(): Unit = {
    when(dataStoreConnection.retrievePublishedDataAsJSON("otherUser", "http://myData")).thenReturn("[]")
    assertEquals("[]", loginAndSendMessageExpectingResponse(RetrievePublishedData(TestSessionId, "otherUser", "http://myData")))
  }

  @Test def sendCallSignal(): Unit = {
    loginAndSendMessage(SendCallSignal(TestSessionId, "foo@bar.net", "my signal"))
    verify(xmppSession).sendCallSignal("foo@bar.net", "my signal")
  }

  @Test def callSignalReceived(): Unit = {
    loginAndAssertThatWebsocketsAreNotifiedOfEvent(PushCallSignalToWebSockets(CallSignalReceipt("foo@bar.net", "message"), `TestWebSocketIds`),
      () => sessionListener.callSignalReceived("foo@bar.net", "message"))
  }

  @Test def sendChatMessage(): Unit = {
    loginAndSendMessage(SendChatMessage(TestSessionId, "foo@bar.net", "message"))
    verify(xmppSession).sendChatMessage("foo@bar.net", "message")
  }

  @Test def chatMessageReceived(): Unit = {
    loginAndAssertThatWebsocketsAreNotifiedOfEvent(PushChatMessageToWebSockets(ChatMessageReceipt("foo@bar.net", "message"), TestWebSocketIds),
      () => sessionListener.chatMessageReceived("foo@bar.net", "message"))
  }

  private def loginAndAssertThatWebsocketsAreNotifiedOfEvent(expectedMessage: AnyRef, executeEvent: () => Unit): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    var dataPusherReceivedExpectedMessage = false

    dataPusher.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case `expectedMessage` =>
            dataPusherReceivedExpectedMessage = true
            NoAutoPilot
        }
    })

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    TestWebSocketIds.foreach(sessionManager ! RegisterWebSocket(TestSessionId, _))

    executeEvent()
    assertTrue(dataPusherReceivedExpectedMessage)
  }

  private def loginAndSendMessage(message: AnyRef): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    sessionManager ! message
  }

  private def loginAndSendMessageExpectingResponse[ResponseType](message: AnyRef, language: String = DefaultLanguage): ResponseType = {
    val sessionManager = createSessionManager(Right(xmppSession))

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, language))

    sendMessageAndGetResponse[ResponseType](sessionManager, message)
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

  private def assertLoginWithTestCredentials(expectedResult: Either[LoginFailure, String], actualResult: Either[LoginFailure, String]) {
    assertEquals(expectedResult, actualResult)
    assertEquals(TestUsername, xmppLoginName)
    assertEquals(TestPassword, xmppPass)
    assertEquals(TestXMPPSettings, xmppSettings)
  }
}
