package tela.web

import java.nio.file.{Path, Paths}
import java.util.UUID
import akka.actor.SupervisorStrategy.Resume
import akka.actor.{Actor, ActorRef, ActorSystem, OneForOneStrategy, Props, SupervisorStrategy}
import akka.pattern.ask
import akka.testkit.{TestActorRef, TestProbe}
import com.typesafe.config.ConfigFactory
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.matchers.should.Matchers._
import play.api.libs.json.{JsValue, Json}
import tela.baseinterfaces._
import tela.web.JSONConversions._
import tela.web.SessionManager._

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.reflect.ClassTag

class SessionManagerSpec extends WebBaseSpec {
  private class TestEnvironment(val xmppSession: XMPPSession,
                                val dataStoreConnection: DataStoreConnection,
                                val actorSystem: ActorSystem,
                                val supervisor: ActorRef,
                                val supervisorTarget: TestProbe,
                                var sessionListener: Option[XMPPSessionListener])

  private def testEnvironment(runTest: TestEnvironment => Unit) = {
    val xmppSession = mock[XMPPSession]
    val dataStoreConnection = mock[DataStoreConnection]

    class Supervisor(target: ActorRef) extends Actor {
      override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
        case thr: Throwable =>
          target ! thr
          Resume
      }

      override def receive: Receive = {
        case anything => sender() ! anything
      }
    }

    implicit val actorSystem = ActorSystem("actor", ConfigFactory.parseString("blocking-operations-dispatcher = akka.actor.default-dispatcher"))
    val supervisorTarget = TestProbe()
    val supervisor = actorSystem.actorOf(Props(new Supervisor(supervisorTarget.ref)))

    runTest(new TestEnvironment(xmppSession, dataStoreConnection, actorSystem, supervisor, supervisorTarget, sessionListener = None))
  }

  "Login" should "return InvalidCredentials error in case of bad login" in testEnvironment { environment =>
    val sessionManager = createSessionManager(Left(LoginFailure.InvalidCredentials), environment)

    val result = sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    result should === (Left(LoginFailure.InvalidCredentials))
  }

  it should "return ConnectionFailure if unable to connect to XMPP server" in testEnvironment { environment =>
    val sessionManager = createSessionManager(Left(LoginFailure.ConnectionFailure), environment)

    val result = sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    result should === (Left(LoginFailure.ConnectionFailure))
  }

  it should "create session with connections to xmpp server and data store on successful login" in testEnvironment { environment =>
    val sessionManager = createSessionManager(Right(environment.xmppSession), environment)

    val result = sendMessageAndGetResponse[Either[LoginFailure, UUID]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    result should === (Right(TestSessionId))
  }

  "GetSession" should "return None is a session does not exist for the given session id" in testEnvironment { environment =>
    val sessionManager = createSessionManager(Right(environment.xmppSession), environment)

    val result = sendMessageAndGetResponse[Option[String]](sessionManager, GetSession(TestSessionId))

    result should === (None)
  }

  it should "return the UserData object associated with a valid session" in testEnvironment { environment =>
    val result = loginAndSendMessageExpectingResponse[Option[UserData]](environment, GetSession(TestSessionId))
    result should === (Some(UserData(TestUsername, DefaultLanguage)))
  }

  "Logout" should "disconnect from xmpp server and datastore, and kill websockets associated with connection" in testEnvironment { environment =>
    val sessionManager = createSessionManager(Right(environment.xmppSession), environment)

    implicit val actorSystem = environment.actorSystem
    val testWebSocketRefs = Set(TestProbe().ref, TestProbe().ref)
    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))
    testWebSocketRefs.foreach(sessionManager ! RegisterWebSocket(TestSessionId, _))

    val deathWatcher = TestProbe()
    testWebSocketRefs.foreach(deathWatcher watch)

    sessionManager ! Logout(TestSessionId)
    verify(environment.xmppSession).disconnect()
    verify(environment.dataStoreConnection).closeConnection()

    testWebSocketRefs.foreach(webSocketRef => deathWatcher.expectTerminated(webSocketRef))

    val result = sendMessageAndGetResponse[Option[String]](sessionManager, GetSession(TestSessionId))

    result should === (None)
  }

  "SetPresence" should "set the given presence state on the XMPP connection" in testEnvironment { environment =>
    loginAndSendMessage(environment, SetPresence(TestSessionId, Presence.Available))
    verify(environment.xmppSession).setPresence(Presence.Available)
  }

  "RegisterWebSocket" should "ignore attempt to register websocket with unknown session" in testEnvironment { environment =>
    val sessionManager = createSessionManager(Right(environment.xmppSession), environment)
    implicit val actorSystem = environment.actorSystem
    val testWebSocket = TestProbe()
    sessionManager ! RegisterWebSocket(TestSessionId, testWebSocket.ref)
    environment.supervisorTarget.expectNoMessage()
  }

  it should "register the given websocket such that it receives future events" in testEnvironment { environment =>
    implicit val actorSystem = environment.actorSystem
    val testWebSocket = TestProbe()
    loginAndSendMessage(environment, RegisterWebSocket(TestSessionId, testWebSocket.ref))
    environment.sessionListener.foreach(_.contactsAdded(Vector.empty))
    testWebSocket.expectMsg(Json.toJson(AddContacts(Vector.empty)))
  }

  "GetLanguages" should "return a LanguageInfo object with default language selected if language not specified on login" in testEnvironment { environment =>
    loginAndSendMessageExpectingResponse[LanguageInfo](environment, GetLanguages(TestSessionId)) should === (TestLanguageInfo)
  }

  it should "return a LanguageInfo object with language specified on login" in testEnvironment { environment =>
    loginAndSendMessageExpectingResponse[LanguageInfo](environment, GetLanguages(TestSessionId), SpanishLanguageCode) should === (LanguageInfo(TestLanguageInfo.languages, SpanishLanguageCode))
  }

  "UnregisterWebSocket" should "stop the given websocket from being notified of future events" in testEnvironment { environment =>
    val sessionManager = createSessionManager(Right(environment.xmppSession), environment)

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    implicit val actorSystem = environment.actorSystem
    val webSockets = Vector(TestProbe(), TestProbe(), TestProbe())
    webSockets.foreach(webSocket => sessionManager ! RegisterWebSocket(TestSessionId, webSocket.ref))

    sessionManager ! UnregisterWebSocket(TestSessionId, webSockets(1).ref)

    environment.sessionListener.foreach(_.contactsAdded(Vector.empty))
    webSockets(0).expectMsg(Json.toJson(AddContacts(Vector.empty)))
    webSockets(1).expectNoMessage()
    webSockets(2).expectMsg(Json.toJson(AddContacts(Vector.empty)))

    //Testing deregister for unknown session. Should probably be doing this as separate test...
    sessionManager.receive(UnregisterWebSocket(UUID.randomUUID(), TestProbe().ref))
  }

  "ChangePassword" should "change password on XMPP connection and send result indicating success" in testEnvironment { environment =>
    when(environment.xmppSession.changePassword(TestPassword, TestNewPassword)).thenReturn(Future.successful(true))
    loginAndSendMessageExpectingResponse[Boolean](environment, ChangePassword(TestSessionId, TestPassword, TestNewPassword)) should === (true)
    verify(environment.xmppSession).changePassword(TestPassword, TestNewPassword)
  }

  it should "return result indicating failure if changing password fails" in testEnvironment { environment =>
    when(environment.xmppSession.changePassword(TestPassword, TestNewPassword)).thenReturn(Future.successful(false))
    loginAndSendMessageExpectingResponse[Boolean](environment, ChangePassword(TestSessionId, TestPassword, TestNewPassword)) should === (false)
  }

  "SetLanguage" should "set the preferred language to the one specified" in testEnvironment { environment =>
    val sessionManager = createSessionManager(Right(environment.xmppSession), environment)

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, SpanishLanguageCode))

    sendMessageAndGetResponse[Option[UserData]](sessionManager, GetSession(TestSessionId)).exists(_.preferredLanguage == SpanishLanguageCode) should === (true)

    sessionManager ! SetLanguage(TestSessionId, DefaultLanguage)

    sendMessageAndGetResponse[Option[UserData]](sessionManager, GetSession(TestSessionId)).exists(_.preferredLanguage == DefaultLanguage) should === (true)
  }

  "GetContactList" should "send the contact list to all registered websockets for the given session" in testEnvironment { environment =>
    val expectedJson = Json.parse(s"""{"$ActionKey":"$AddContactsAction","$DataKey":[""" +
      s"""{"$ContactKey":"$TestContact1","$PresenceKey":"${Presence.Available.toString.toLowerCase}"},""" +
      s"""{"$ContactKey":"$TestContact2","$PresenceKey":"${Presence.Away.toString.toLowerCase}"}]}""")

    val sessionManager = createSessionManager(Right(environment.xmppSession), environment)

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    implicit val actorSystem = environment.actorSystem
    val webSockets = Vector(TestProbe(), TestProbe(), TestProbe())
    webSockets.foreach(webSocket => sessionManager ! RegisterWebSocket(TestSessionId, webSocket.ref))

    when(environment.xmppSession.getContactList()).thenAnswer(new Answer[Unit] {
      override def answer(invocation: InvocationOnMock): Unit = {
        environment.sessionListener.foreach(_.contactsAdded(Vector(ContactInfo(TestContact1, Presence.Available), ContactInfo(TestContact2, Presence.Away))))
      }
    })

    sessionManager ! GetContactList(TestSessionId)

    webSockets.foreach(_.expectMsg(expectedJson))
  }

  "presenceChanged" should "notify websockets of changed presence" in testEnvironment { environment =>
    val expectedJson = Json.parse(s"""{"$ActionKey":"$PresenceUpdateAction","$DataKey":{"$ContactKey":"$TestContact1","$PresenceKey":"${Presence.Away.toString.toLowerCase}"}}""")

    loginAndAssertThatWebsocketsAreNotifiedOfEvent(environment, expectedJson,
      () => environment.sessionListener.foreach(_.presenceChanged(ContactInfo(TestContact1, Presence.Away))))
  }

  "selfPresenceChanged" should "notify websockets of changed self presence" in testEnvironment { environment =>
    val expectedJson = Json.parse(s"""{"$ActionKey":"$SelfPresenceUpdateAction","$DataKey":{"$PresenceKey":"${Presence.DoNotDisturb.toString.toLowerCase}"}}""")

    loginAndAssertThatWebsocketsAreNotifiedOfEvent(environment, expectedJson,
      () => environment.sessionListener.foreach(_.selfPresenceChanged(Presence.DoNotDisturb)))
  }

  "AddContact" should "add the specified contact in the underlying xmpp session" in testEnvironment { environment =>
    loginAndSendMessage(environment, AddContact(TestSessionId, TestContact1))
    verify(environment.xmppSession).addContact(TestContact1)
  }

  "PublishData" should "write the given information to the data store and instruct the data store to publish it" in testEnvironment { environment =>
    when(environment.dataStoreConnection.insertJSON("[]")).thenReturn(Future.successful(()))
    when(environment.dataStoreConnection.publish(TestDataObjectUri)).thenReturn(Future.successful(()))
    loginAndSendMessage(environment, PublishData(TestSessionId, "[]", TestDataObjectUri))
    verify(environment.dataStoreConnection).insertJSON("[]")
    verify(environment.dataStoreConnection).publish(TestDataObjectUri)
  }

  "RetrieveData" should "retrieve the given information from the data store" in testEnvironment { environment =>
    when(environment.dataStoreConnection.retrieveJSON(TestDataObjectUri)).thenReturn(Future.successful("[]"))
    loginAndSendMessageExpectingResponse[String](environment, RetrieveData(TestSessionId, TestDataObjectUri)) should === ("[]")
  }

  "RetrievePublishedData" should "ask the data store to retrieve data published by another user" in testEnvironment { environment =>
    when(environment.dataStoreConnection.retrievePublishedDataAsJSON(TestContact1, TestDataObjectUri)).thenReturn(Future.successful("[]"))
    loginAndSendMessageExpectingResponse[String](environment, RetrievePublishedData(TestSessionId, TestContact1, TestDataObjectUri)) should === ("[]")
  }

  "SendCallSignal" should "send the given call signal via the xmpp connection" in testEnvironment { environment =>
    loginAndSendMessage(environment, SendCallSignal(TestSessionId, TestContact1, TestCallSignalData))
    verify(environment.xmppSession).sendCallSignal(TestContact1, TestCallSignalData)
  }

  "callSignalReceived" should "notify websockets of call signal" in testEnvironment { environment =>
    val expectedJson: JsValue = Json.parse(s"""{"$ActionKey":"$CallSignalReceived","$DataKey":{"$CallSignalSenderKey":"$TestContact1","$CallSignalDataKey":$TestCallSignalData}}""")
    loginAndAssertThatWebsocketsAreNotifiedOfEvent(environment, expectedJson,
      () => environment.sessionListener.foreach(_.callSignalReceived(TestContact1, TestCallSignalData)))
  }

  "SendChatMessage" should "send the given chat message via the xmpp connection" in testEnvironment { environment =>
    loginAndSendMessage(environment, SendChatMessage(TestSessionId, TestContact1, TestChatMessage))
    verify(environment.xmppSession).sendChatMessage(TestContact1, TestChatMessage)
  }

  "chatMessageReceived" should "notify websockets of chat message" in testEnvironment { environment =>
    val expectedJson: JsValue = Json.parse(s"""{"$ActionKey":"$ChatMessageReceived","$DataKey":{"$ChatMessageSenderKey":"$TestContact1","$ChatMessageDataKey":"$TestChatMessage"}}""")
    loginAndAssertThatWebsocketsAreNotifiedOfEvent(environment, expectedJson,
      () => environment.sessionListener.foreach(_.chatMessageReceived(TestContact1, TestChatMessage)))
  }

  "StoreMediaItem" should "instruct the data store to store the file at the specified location" in testEnvironment { environment =>
    val tempFile = Paths.get("tempFile")
    val originalFileName = Paths.get("myFile.txt")
    loginAndSendMessage(environment, StoreMediaItem(TestSessionId, tempFile, originalFileName, Some(TestDateAsLocalDateTime)))
    verify(environment.dataStoreConnection).storeMediaItem(tempFile, originalFileName, Some(TestDateAsLocalDateTime))
  }

  "RetrieveMediaItem" should "get the filename for the given hash from the data store" in testEnvironment { environment =>
    when(environment.dataStoreConnection.retrieveMediaItem("thisIsAHash")).thenReturn(Future.successful(Some(Paths.get("/my/file"))))
    loginAndSendMessageExpectingResponse[Option[Path]](environment, RetrieveMediaItem(TestSessionId, "thisIsAHash")) should === (Some(Paths.get("/my/file")))
  }

  "SPARQLQuery" should "send the given SPARQL query to the data store and return the response" in testEnvironment { environment =>
    val sampleSparqlQuery = "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }"
    when(environment.dataStoreConnection.runSPARQLQuery(sampleSparqlQuery)).thenReturn(Future.successful("[]"))
    loginAndSendMessageExpectingResponse[String](environment, SPARQLQuery(TestSessionId, sampleSparqlQuery)) should === ("[]")
  }

  "events on non-existent session" should "not cause exceptions" in testEnvironment { environment =>
    loginAndSendMessage(environment, Logout(TestSessionId))
    environment.sessionListener.foreach(_.contactsAdded(Vector.empty))
    environment.sessionListener.foreach(_.presenceChanged(ContactInfo(TestContact1, Presence.DoNotDisturb)))
    environment.sessionListener.foreach(_.contactsRemoved(Vector.empty))
    environment.sessionListener.foreach(_.callSignalReceived(TestContact1, TestCallSignalData))
    environment.sessionListener.foreach(_.chatMessageReceived(TestContact1, TestChatMessage))
    environment.supervisorTarget.expectNoMessage()
  }

  private def loginAndAssertThatWebsocketsAreNotifiedOfEvent(environment: TestEnvironment, expectedMessage: AnyRef, executeEvent: () => Unit): Unit = {
    val sessionManager = createSessionManager(Right(environment.xmppSession), environment)

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    implicit val actorSystem = environment.actorSystem
    val webSockets = Vector(TestProbe(), TestProbe(), TestProbe())
    webSockets.foreach(webSocket => sessionManager ! RegisterWebSocket(TestSessionId, webSocket.ref))

    executeEvent()
    webSockets.foreach(_.expectMsg(expectedMessage))
  }

  private def loginAndSendMessage(environment: TestEnvironment, message: AnyRef): Unit = {
    val sessionManager = createSessionManager(Right(environment.xmppSession), environment)

    sendMessageAndGetResponse[Either[LoginFailure, String]](sessionManager, Login(TestUsername, TestPassword, DefaultLanguage))

    sessionManager ! message
  }

  private def loginAndSendMessageExpectingResponse[ResponseType : ClassTag](environment: TestEnvironment, message: AnyRef, language: String = DefaultLanguage): ResponseType = {
    val sessionManager = createSessionManager(Right(environment.xmppSession), environment)

    sendMessageAndGetResponse[Either[LoginFailure, UUID]](sessionManager, Login(TestUsername, TestPassword, language))

    sendMessageAndGetResponse[ResponseType](sessionManager, message)
  }

  private def sendMessageAndGetResponse[ResponseType : ClassTag](actor: ActorRef, message: Any): ResponseType = {
    Await.result((actor ? message).mapTo[ResponseType], GeneralTimeoutAsDuration)
  }

  private def createSessionManager(result: Either[LoginFailure, XMPPSession], environment: TestEnvironment): TestActorRef[SessionManager] = {
    def createXMPPConnection(user: String, pass: String, settings: XMPPSettings, sessionListener: XMPPSessionListener, executionContext: ExecutionContext): Future[Either[LoginFailure, XMPPSession]] = {
      user should === (TestUsername)
      pass should === (TestPassword)
      settings should === (TestXMPPSettings)
      environment.sessionListener = Some(sessionListener)
      Future.successful(result)
    }

    def createDataStoreConnection(user: String, xmppSession: XMPPSession, executionContext: ExecutionContext): Future[DataStoreConnection] = {
      user should === (TestUsername)
      (xmppSession eq environment.xmppSession) should === (true)
      Future.successful(environment.dataStoreConnection)
    }

    //Send a message to the supervisor, and when we get a response, we know that it has come up
    //Otherwise it's possible that tests might fail intermittently because the supervisor hasn't started when we create the TestActorRef
    //For reasons unknown, when running this on a Debian instance on VirtualBox, we need to send (and await the result of)
    //2 messages rather than 1. Perhaps on other platforms 3 are needed :-)
    Await.result(environment.supervisor ? "testMessage1", GeneralTimeoutAsDuration) should === ("testMessage1")
    Await.result(environment.supervisor ? "testMessage2", GeneralTimeoutAsDuration) should === ("testMessage2")

    implicit val actorSystem = environment.actorSystem
    //TODO Would everything work pretty much the same if we created a regular actor rather than using TestActorRef?
    TestActorRef(Props(classOf[SessionManager], createXMPPConnection _, createDataStoreConnection _, TestLanguageInfo.languages, TestXMPPSettings, () => TestSessionId), environment.supervisor)
  }
}
