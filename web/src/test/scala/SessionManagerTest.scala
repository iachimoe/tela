package tela.web

import java.util.concurrent.TimeUnit

import akka.actor.SupervisorStrategy.Resume
import akka.actor.{Actor, ActorRef, ActorSystem, OneForOneStrategy, Props, SupervisorStrategy}
import akka.testkit.{TestActorRef, TestProbe}
import akka.pattern.ask
import org.junit.Assert._
import org.junit.{Before, Test}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.junit.AssertionsForJUnit
import org.scalatest.mock.MockitoSugar
import play.api.libs.json.{JsValue, Json}
import tela.baseinterfaces._
import tela.web.JSONConversions._
import tela.web.SessionManager._

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class SessionManagerTest extends AssertionsForJUnit with MockitoSugar {
  private val TestUsername = "myUser"
  private val TestPassword = "myPass"
  private val TestSessionId = "aaaaaaaaa"
  private val TestXMPPSettings = XMPPSettings("localhost", 5222, "example.com", "disabled")

  private val TestUser1 = "user1@example.com"
  private val TestUser2 = "user2@example.com"
  private val TestCallSignal = """{"type":"offer","sdp":"v=0"}"""
  private val TestMessage = "message"

  private val Languages = LanguageInfo(Map("en" -> "English", "es" -> "Spanish"), DefaultLanguage)

  implicit private var actorSystem: ActorSystem = null
  private var xmppSession: XMPPSession = null
  private var xmppLoginName: String = null
  private var xmppPass: String = null
  private var xmppSettings: XMPPSettings = null
  private var dataStoreUsername: String = null
  private var xmppSessionSuppliedToDataStore: XMPPSession = null
  private var dataStoreConnection: DataStoreConnection = null
  private var sessionListener: XMPPSessionListener = null
  private var testWebSocketActors: Set[TestProbe] = null
  private var testWebSocketRefs: Set[ActorRef] = null
  private var supervisor: ActorRef = null
  private var supervisorTarget: TestProbe = null

  @Before def initialize(): Unit = {
    xmppSession = mock[XMPPSession]
    xmppLoginName = null
    xmppPass = null
    dataStoreUsername = null
    xmppSessionSuppliedToDataStore = null
    dataStoreConnection = mock[DataStoreConnection]
    sessionListener = null
    actorSystem = ActorSystem("actor")

    val testWebSocket1 = TestProbe()
    val testWebSocket2 = TestProbe()
    testWebSocketActors = Set(testWebSocket1, testWebSocket2)
    testWebSocketRefs = Set(testWebSocket1.ref, testWebSocket2.ref)

    class Supervisor(target: ActorRef) extends Actor {
      override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
        case thr: Throwable =>
          target ! thr
          Resume
      }

      override def receive: Receive = {
        case anything => sender ! anything
      }
    }

    supervisorTarget = TestProbe()
    supervisor = actorSystem.actorOf(Props(new Supervisor(supervisorTarget.ref)))
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
    assertEquals(TestUsername, result.get.username)
    assertEquals(DefaultLanguage, result.get.preferredLanguage)
  }

  @Test def logout(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))
    testWebSocketRefs.foreach(sessionManager ! RegisterWebSocket(TestSessionId, _))

    val deathWatcher = TestProbe()
    testWebSocketRefs.foreach(deathWatcher watch)

    sessionManager ! Logout(TestSessionId)
    verify(xmppSession).disconnect()
    verify(dataStoreConnection).closeConnection()

    testWebSocketRefs.foreach(webSocketRef => deathWatcher.expectTerminated(webSocketRef))

    val result = sendMessageAndGetResponse[Option[String]](sessionManager, GetSession(TestSessionId))

    assertEquals(None, result)
  }

  @Test def setPresence(): Unit = {
    loginAndSendMessage(SetPresence(TestSessionId, Presence.Available))
    verify(xmppSession).setPresence(Presence.Available)
  }

  @Test def registerWebSocket_UnknownSession(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))
    val testWebSocket = TestProbe()
    sessionManager ! RegisterWebSocket(TestSessionId, testWebSocket.ref)
    supervisorTarget.expectNoMsg()
  }

  @Test def registerWebSocket(): Unit = {
    val testWebSocket = TestProbe()
    loginAndSendMessage(RegisterWebSocket(TestSessionId, testWebSocket.ref))
    sessionListener.contactsAdded(Nil)
    testWebSocket.expectMsg(Json.toJson(AddContacts(Nil)))
  }

  @Test def getLanguages(): Unit = {
    assertEquals(Languages, loginAndSendMessageExpectingResponse[LanguageInfo](GetLanguages(TestSessionId)))
  }

  @Test def getLanguages_NonDefaultLanguage(): Unit = {
    assertEquals(LanguageInfo(Languages.languages, "es"), loginAndSendMessageExpectingResponse[LanguageInfo](GetLanguages(TestSessionId), "es"))
  }

  @Test def deregisterWebSocket(): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    val webSockets = List(TestProbe(), TestProbe(), TestProbe())
    val webSocketRefs = webSockets.map(_.ref)
    sessionManager ! RegisterWebSocket(TestSessionId, webSocketRefs(0))
    sessionManager ! RegisterWebSocket(TestSessionId, webSocketRefs(1))
    sessionManager ! RegisterWebSocket(TestSessionId, webSocketRefs(2))

    sessionManager ! UnregisterWebSocket(TestSessionId, webSocketRefs(1))

    sessionListener.contactsAdded(Nil)
    webSockets(0).expectMsg(Json.toJson(AddContacts(Nil)))
    webSockets(1).expectNoMsg()
    webSockets(2).expectMsg(Json.toJson(AddContacts(Nil)))

    //Testing deregister for unknown session. Should probably be doing this as separate test...
    sessionManager.receive(UnregisterWebSocket("asdf", TestProbe().ref))
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

    assertEquals("es", sendMessageAndGetResponse[Option[UserData]](sessionManager, GetSession(TestSessionId)).get.preferredLanguage)

    sessionManager ! SetLanguage(TestSessionId, DefaultLanguage)

    assertEquals(DefaultLanguage, sendMessageAndGetResponse[Option[UserData]](sessionManager, GetSession(TestSessionId)).get.preferredLanguage)
  }

  @Test def getContactList(): Unit = {
    val expectedJson = Json.parse(s"""{"$ActionKey":"$AddContactsAction","$DataKey":[""" +
      s"""{"$ContactKey":"$TestUser1","$PresenceKey":"${Presence.Available.toString.toLowerCase}"},""" +
      s"""{"$ContactKey":"$TestUser2","$PresenceKey":"${Presence.Away.toString.toLowerCase}"}]}""")

    val sessionManager = createSessionManager(Right(xmppSession))

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    testWebSocketRefs.foreach(sessionManager ! RegisterWebSocket(TestSessionId, _))

    when(xmppSession.getContactList).thenAnswer(new Answer[Unit] {
      override def answer(invocation: InvocationOnMock): Unit = {
        sessionListener.contactsAdded(List(ContactInfo(TestUser1, Presence.Available), ContactInfo(TestUser2, Presence.Away)))
      }
    })

    sessionManager ! GetContactList(TestSessionId)

    testWebSocketActors.foreach(_.expectMsg(expectedJson))
  }

  @Test def presenceUpdated(): Unit = {
    val expectedJson = Json.parse(s"""{"$ActionKey":"$PresenceUpdateAction","$DataKey":{"$ContactKey":"$TestUser1","$PresenceKey":"${Presence.Away.toString.toLowerCase}"}}""")

    loginAndAssertThatWebsocketsAreNotifiedOfEvent(expectedJson,
      () => sessionListener.presenceChanged(ContactInfo(TestUser1, Presence.Away)))
  }

  @Test def addContact(): Unit = {
    loginAndSendMessage(AddContact(TestSessionId, TestUser1))
    verify(xmppSession).addContact(TestUser1)
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
    loginAndSendMessage(SendCallSignal(TestSessionId, TestUser1, TestCallSignal))
    verify(xmppSession).sendCallSignal(TestUser1, TestCallSignal)
  }

  @Test def callSignalReceived(): Unit = {
    val expectedJson: JsValue = Json.parse(s"""{"$ActionKey":"$CallSignalReceived","$DataKey":{"$CallSignalSenderKey":"$TestUser1","$CallSignalDataKey":$TestCallSignal}}""")
    loginAndAssertThatWebsocketsAreNotifiedOfEvent(expectedJson,
      () => sessionListener.callSignalReceived(TestUser1, TestCallSignal))
  }

  @Test def sendChatMessage(): Unit = {
    loginAndSendMessage(SendChatMessage(TestSessionId, TestUser1, TestMessage))
    verify(xmppSession).sendChatMessage(TestUser1, TestMessage)
  }

  @Test def chatMessageReceived(): Unit = {
    val expectedJson: JsValue = Json.parse(s"""{"$ActionKey":"$ChatMessageReceived","$DataKey":{"$ChatMessageSenderKey":"$TestUser1","$ChatMessageDataKey":"$TestMessage"}}""")
    loginAndAssertThatWebsocketsAreNotifiedOfEvent(expectedJson,
      () => sessionListener.chatMessageReceived(TestUser1, TestMessage))
  }

  @Test def storeMediaItem(): Unit = {
    loginAndSendMessage(StoreMediaItem(TestSessionId, "tempFile", "myFile.txt"))
    verify(dataStoreConnection).storeMediaItem("tempFile", "myFile.txt")
  }

  @Test def retrieveMediaItem(): Unit = {
    when(dataStoreConnection.retrieveMediaItem("thisIsAHash")).thenReturn(Some("/my/file"))
    assertEquals(Some("/my/file"), loginAndSendMessageExpectingResponse[Option[String]](RetrieveMediaItem(TestSessionId, "thisIsAHash")))
  }

  @Test def sparqlQuery(): Unit = {
    val sampleSparqlQuery = "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }"
    when(dataStoreConnection.runSPARQLQuery(sampleSparqlQuery)).thenReturn("[]")
    assertEquals("[]", loginAndSendMessageExpectingResponse[String](SPARQLQuery(TestSessionId, sampleSparqlQuery)))
  }

  @Test def textSearch(): Unit = {
    val searchResult: List[String] = List("result1", "result2")
    val query: String = "blah"
    when(dataStoreConnection.textSearch(query)).thenReturn(searchResult)
    assertEquals(TextSearchResult(searchResult), loginAndSendMessageExpectingResponse[TextSearchResult](TextSearch(TestSessionId, query)))
  }

  @Test def eventsOnNonExistentSessionDoNotCauseExceptions(): Unit = {
    loginAndSendMessage(Logout(TestSessionId))
    sessionListener.contactsAdded(Nil)
    sessionListener.presenceChanged(ContactInfo(TestUser1, Presence.DoNotDisturb))
    sessionListener.contactsRemoved(Nil)
    sessionListener.callSignalReceived(TestUser1, TestCallSignal)
    sessionListener.chatMessageReceived(TestUser1, TestMessage)
    supervisorTarget.expectNoMsg()
  }

  private def loginAndAssertThatWebsocketsAreNotifiedOfEvent(expectedMessage: AnyRef, executeEvent: () => Unit): Unit = {
    val sessionManager = createSessionManager(Right(xmppSession))

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    testWebSocketRefs.foreach(sessionManager ! RegisterWebSocket(TestSessionId, _))

    executeEvent()
    testWebSocketActors.foreach(_.expectMsg(expectedMessage))
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

  private def sendMessageAndGetResponse[ResponseType](actor: ActorRef, message: Any): ResponseType = {
    Await.result(actor ? message, timeout.duration).asInstanceOf[ResponseType]
  }

  private def createSessionManager(result: Either[LoginFailure, XMPPSession]): TestActorRef[SessionManager] = {
    def createXMPPConnection(user: String, pass: String, settings: XMPPSettings, sessionListener: XMPPSessionListener) = {
      xmppLoginName = user
      xmppPass = pass
      xmppSettings = settings
      this.sessionListener = sessionListener
      result
    }

    def createDataStoreConnection(user: String, xmppSession: XMPPSession) = {
      dataStoreUsername = user
      xmppSessionSuppliedToDataStore = xmppSession
      dataStoreConnection
    }

    //Send a message to the supervisor, and when we get a response, we know that it has come up
    //Otherwise it's possible that tests might fail intermittently because the supervisor hasn't started when we create the TestActorRef
    //For reasons unknown, when running this on a Debian instance on VirtualBox, we need to send (and await the result of)
    //2 messages rather than 1. Perhaps on other platforms 3 are needed :-)
    assertEquals("testMessage1", Await.result(supervisor ? "testMessage1", Duration(TimeoutDurationInSeconds, TimeUnit.SECONDS)))
    assertEquals("testMessage2", Await.result(supervisor ? "testMessage2", Duration(TimeoutDurationInSeconds, TimeUnit.SECONDS)))
    TestActorRef(Props(classOf[SessionManager], createXMPPConnection _, createDataStoreConnection _, Languages.languages, TestXMPPSettings, () => TestSessionId), supervisor)
  }

  private def assertLoginWithTestCredentials(expectedResult: Either[LoginFailure, String], actualResult: Either[LoginFailure, String]) {
    assertEquals(expectedResult, actualResult)
    assertEquals(TestUsername, xmppLoginName)
    assertEquals(TestPassword, xmppPass)
    assertEquals(TestXMPPSettings, xmppSettings)
  }
}
