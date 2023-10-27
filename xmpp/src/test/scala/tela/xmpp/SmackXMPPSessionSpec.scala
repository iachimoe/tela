package tela.xmpp

import org.jivesoftware.smack.ConnectionConfiguration.SecurityMode

import java.io.StringReader
import org.jivesoftware.smack.SmackException.EndpointConnectionException
import org.jivesoftware.smack._
import org.jivesoftware.smack.filter.StanzaFilter
import org.jivesoftware.smack.iqrequest.{AbstractIqRequestHandler, IQRequestHandler}
import org.jivesoftware.smack.packet.IQ.Type
import org.jivesoftware.smack.packet.Presence.Mode
import org.jivesoftware.smack.packet.id.StandardStanzaIdSource
import org.jivesoftware.smack.packet.{Presence, _}
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.roster.Roster
import org.jivesoftware.smack.roster.Roster.SubscriptionMode
import org.jivesoftware.smack.roster.packet.RosterPacket
import org.jivesoftware.smack.roster.packet.RosterPacket.Item
import org.jivesoftware.smack.sasl.packet.SaslNonza.SASLFailure
import org.jivesoftware.smack.sasl.{SASLError, SASLErrorException}
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smackx.iqregister.packet.Registration
import org.jivesoftware.smackx.ping.packet.Ping
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.mockito.{ArgumentCaptor, ArgumentMatcher}
import org.scalatest.matchers.should.Matchers._
import org.scalatest.OptionValues
import tela.baseinterfaces._
import tela.xmpp.SmackXMPPSession.{CallSignal, CallSignalElementName, DefaultPriority, DefaultStatusText, TelaURN}

import scala.concurrent.Await
import scala.jdk.CollectionConverters._
import scala.xml.{Elem, NodeSeq, XML}
import scala.concurrent.ExecutionContext.global

//TODO Smack seems to be getting harder to test with each successive version.
//Using PowerMockito to more effectively mock AbstractXMPPConnection may be the most practical solution
class SmackXMPPSessionSpec extends BaseSpec with OptionValues {
  private val SmackUsernameKey = "username"
  private val SmackPasswordKey = "password"

  private val TestResource = "res"
  private val TestBareJid = s"$TestUsername@$TestDomain"
  private val TestFullJid = s"$TestBareJid/$TestResource"
  //TODO Reference constant for callSignal element name
  private val RawTestCallSignalPacket = <callSignal xmlns={SmackXMPPSession.TelaURN}>{TestCallSignalData}</callSignal>.toString()

  private val AsyncWaitTimeoutMs = 5000

  private class TestEnvironment(val connection: TestableXMPPConnection, val sessionListener: XMPPSessionListener)

  private def testEnvironment(runTest: TestEnvironment => Unit): Unit = {
    val xmppConnection = new TestableXMPPConnection()
    runTest(new TestEnvironment(spy(xmppConnection), mock[XMPPSessionListener]))
  }

  "connectToServer" should "return a session object when valid credentials are supplied" in testEnvironment { environment =>
    val session = connectToServer(environment.connection, environment.sessionListener)
    session.isRight should === (true)
    Roster.getInstanceFor(environment.connection).getSubscriptionMode should === (SubscriptionMode.manual)
    verify(environment.connection).connect()
    verify(environment.connection).login(TestUsername, TestPassword, Resourcepart.from(SmackXMPPSession.DefaultResourceName))
  }

  it should "return an appropriate error and disconnect underlying XMPP connection when invalid credentials are supplied" in testEnvironment { environment =>
    doThrow(new SASLErrorException("", new SASLFailure(SASLError.not_authorized.toString))).when(
      environment.connection
    ).login(TestUsername, TestPassword, Resourcepart.from(SmackXMPPSession.DefaultResourceName))
    connectToServer(environment.connection, environment.sessionListener) should === (Left(LoginFailure.InvalidCredentials))
    verify(environment.connection).disconnect()
  }

  it should "return an appropriate error and disconnect underlying XMPP connection when connection fails" in testEnvironment { environment =>
    import scala.jdk.CollectionConverters._
    when(environment.connection.connect()).thenThrow(EndpointConnectionException.from(Nil.asJava, Nil.asJava))
    connectToServer(environment.connection, environment.sessionListener) should === (Left(LoginFailure.ConnectionFailure))
    verify(environment.connection).disconnect()
  }

  "disconnect" should "disconnect underlying session" in testEnvironment { environment =>
    Await.result(connectToServerAndGetSession(environment.connection, environment.sessionListener).disconnect(), TestAwaitTimeout)
    verify(environment.connection).disconnect()
  }

  "setPresence" should "set presence to available when requested" in testEnvironment { environment =>
    val session = connectToServerAndGetSession(environment.connection, environment.sessionListener)
    setPresence(tela.baseinterfaces.Presence.Available, session)
    verifyPresencePacketSent(environment.connection, createSmackPresence(Presence.Mode.available))
  }

  it should "set presence to away when requested" in testEnvironment { environment =>
    val session = connectToServerAndGetSession(environment.connection, environment.sessionListener)
    setPresence(tela.baseinterfaces.Presence.Away, session)
    verifyPresencePacketSent(environment.connection, createSmackPresence(Presence.Mode.away))
  }

  it should "set presence to DND when requested" in testEnvironment { environment =>
    val session = connectToServerAndGetSession(environment.connection, environment.sessionListener)
    setPresence(tela.baseinterfaces.Presence.DoNotDisturb, session)
    verifyPresencePacketSent(environment.connection, createSmackPresence(Presence.Mode.dnd))
  }

  it should "ignore request to set presence to Unknown" in testEnvironment { environment =>
    val session = connectToServerAndGetSession(environment.connection, environment.sessionListener)
    setPresence(tela.baseinterfaces.Presence.Unknown, session)
    verify(environment.connection, never()).sendStanzaInternal(any())
  }

  private def setPresence(presence: tela.baseinterfaces.Presence, session: XMPPSession): Unit = {
    Await.result(session.setPresence(presence), TestAwaitTimeout)
  }

  "changePassword" should "create a second connection to test old password and then use it to change the password" in testEnvironment { environment =>
    val session: XMPPSession = createSessionWithUsername(environment.connection, environment.sessionListener)

    val changePasswordConnection = spy(new TestableXMPPConnection())
    doCallRealMethod().when(changePasswordConnection).setUser(TestFullJid)
    changePasswordConnection.setUser(TestFullJid)
    when(changePasswordConnection.getUser).thenCallRealMethod()

    val expectedRegistrationPacket: Registration = new Registration(Map(SmackUsernameKey -> TestUsername, SmackPasswordKey -> TestNewPassword).asJava)
    expectedRegistrationPacket.setType(IQ.Type.set)

    val packetCollector = StanzaCollectorHelper.createStanzaCollector(changePasswordConnection)
    //Adding an arbitrary packet to make the test pass :/
    StanzaCollectorHelper.callProcessStanza(packetCollector, new Ping())
    doReturn(packetCollector).when(changePasswordConnection).createStanzaCollectorAndSend(
      isA(classOf[StanzaFilter]), argThat(new RegistrationMatcher(expectedRegistrationPacket)))

    Await.result(session.asInstanceOf[SmackXMPPSession].changePassword(TestPassword, TestNewPassword, changePasswordConnection),
      TestAwaitTimeout) should === (true)

    verify(changePasswordConnection).connect()
    verify(changePasswordConnection).login(TestUsername, TestPassword, Resourcepart.from(SmackXMPPSession.ChangePasswordResource))
    verify(changePasswordConnection).createStanzaCollectorAndSend(isA(classOf[StanzaFilter]), argThat(new RegistrationMatcher(expectedRegistrationPacket)))
    verify(changePasswordConnection).disconnect()
  }

  it should "return false if the old password supplied is incorrect" in testEnvironment { environment =>
    val session: XMPPSession = createSessionWithUsername(environment.connection, environment.sessionListener)

    val changePasswordConnection = mock[TestableXMPPConnection]
    when(changePasswordConnection.login(TestUsername, TestPassword, Resourcepart.from(SmackXMPPSession.ChangePasswordResource))).thenThrow(
      new SASLErrorException("", new SASLFailure(SASLError.not_authorized.toString))
    )

    Await.result(session.asInstanceOf[SmackXMPPSession].changePassword(TestPassword, TestNewPassword, changePasswordConnection),
      TestAwaitTimeout) should === (false)
    verify(changePasswordConnection).disconnect()
  }

  it should "not change password and return false if new password is empty string" in testEnvironment { environment =>
    val session = connectToServerAndGetSession(environment.connection, environment.sessionListener)
    val changePasswordConnection = mock[TestableXMPPConnection]
    Await.result(session.asInstanceOf[SmackXMPPSession].changePassword(TestPassword, "", changePasswordConnection),
      TestAwaitTimeout) should === (false)
    verify(changePasswordConnection).disconnect()
  }

  //TODO This fails intermittently, might be some kind of race condition in Smack
  "getContactList" should "send the user's contacts and self presence to the session listener" in testEnvironment { environment =>
    val session = createSessionWithUsername(environment.connection, environment.sessionListener)

    val requestHandlerCaptor: ArgumentCaptor[AbstractIqRequestHandler] = ArgumentCaptor.forClass(classOf[AbstractIqRequestHandler])
    verify(environment.connection, atLeastOnce()).registerIQRequestHandler(requestHandlerCaptor.capture())
    val contactListRequestHandler = getValueFromArgumentCaptor(requestHandlerCaptor, 0)

    val packetListenerCaptor: ArgumentCaptor[StanzaListener] = ArgumentCaptor.forClass(classOf[StanzaListener])
    verify(environment.connection, atLeastOnce()).addSyncStanzaListener(packetListenerCaptor.capture(), any[StanzaFilter]())
    // We use the index 0 because the roster listener gets added first, then the chat listener (in accordance with our connectToServer method)
    val stanzaListener = getValueFromArgumentCaptor(packetListenerCaptor, 0)

    sendContactAddedEventToHandler(contactListRequestHandler, TestContact1)
    sendPresenceToStanzaListener(stanzaListener, TestContact1, Presence.Mode.away)

    sendContactAddedEventToHandler(contactListRequestHandler, TestContact2)
    sendPresenceToStanzaListener(stanzaListener, TestContact2, Presence.Mode.away)

    sendPresenceToStanzaListener(stanzaListener, TestBareJid, Presence.Mode.dnd)

    reset(environment.sessionListener) //Need to do this to ensure the calls to add the contacts in the first place don't interfere with results

    Await.result(session.getContactList(), TestAwaitTimeout)

    //It seems that the order that the contacts come back in depends on the precise content of the JIDs. Changing the values of the test contacts may change the order
    verify(environment.sessionListener).contactsAdded(Vector(ContactInfo(TestContact1, tela.baseinterfaces.Presence.Away), ContactInfo(TestContact2, tela.baseinterfaces.Presence.Away)))
    verify(environment.sessionListener).selfPresenceChanged(tela.baseinterfaces.Presence.DoNotDisturb)
  }

  private def sendPresenceToStanzaListener(stanzaListener: StanzaListener, bareJid: String, mode: Presence.Mode): Unit = {
    val presence = createSmackPresence(mode)
    presence.setFrom(JidCreate.bareFrom(bareJid))
    stanzaListener.processStanza(presence)
  }

  "presenceChanged" should "set the user's presence to the given value" in testEnvironment { environment =>
    createSessionWithUsername(environment.connection, environment.sessionListener)

    val packetListenerCaptor: ArgumentCaptor[StanzaListener] = ArgumentCaptor.forClass(classOf[StanzaListener])
    verify(environment.connection, atLeastOnce()).addSyncStanzaListener(packetListenerCaptor.capture(), any[StanzaFilter]())
    // We use the index 0 because the roster listener gets added first, then the chat listener (in accordance with our connectToServer method)
    val stanzaListener = getValueFromArgumentCaptor(packetListenerCaptor, 0)

    val requestHandlerCaptor: ArgumentCaptor[AbstractIqRequestHandler] = ArgumentCaptor.forClass(classOf[AbstractIqRequestHandler])
    verify(environment.connection, atLeastOnce()).registerIQRequestHandler(requestHandlerCaptor.capture())
    val contactListRequestHandler = getValueFromArgumentCaptor(requestHandlerCaptor, 0)

    val contactAddedPacket1 = new RosterPacket()
    val item1 = new Item(JidCreate.entityBareFrom(TestContact1), TestContact1) //TODO This is duplicated in assertThatReceiptOfPresenceChangeForContact1IsHandledCorrectly
    item1.setItemType(RosterPacket.ItemType.both)
    contactAddedPacket1.addRosterItem(item1)

    contactListRequestHandler.handleIQRequest(contactAddedPacket1)

    assertThatReceiptOfPresenceChangeForContact1IsHandledCorrectly(tela.baseinterfaces.Presence.Unknown, Presence.Type.unavailable, Presence.Mode.available, environment.sessionListener, stanzaListener)
    assertThatReceiptOfPresenceChangeForContact1IsHandledCorrectly(tela.baseinterfaces.Presence.Available, Presence.Type.available, Presence.Mode.available, environment.sessionListener, stanzaListener)
    assertThatReceiptOfPresenceChangeForContact1IsHandledCorrectly(tela.baseinterfaces.Presence.Away, Presence.Type.available, Presence.Mode.away, environment.sessionListener, stanzaListener)
    assertThatReceiptOfPresenceChangeForContact1IsHandledCorrectly(tela.baseinterfaces.Presence.Available, Presence.Type.available, Presence.Mode.chat, environment.sessionListener, stanzaListener)
    assertThatReceiptOfPresenceChangeForContact1IsHandledCorrectly(tela.baseinterfaces.Presence.DoNotDisturb, Presence.Type.available, Presence.Mode.dnd, environment.sessionListener, stanzaListener)
    assertThatReceiptOfPresenceChangeForContact1IsHandledCorrectly(tela.baseinterfaces.Presence.Away, Presence.Type.available, Presence.Mode.xa, environment.sessionListener, stanzaListener)
    assertThatReceiptOfPresenceChangeForContact1IsHandledCorrectly(tela.baseinterfaces.Presence.Available, Presence.Type.available, null, environment.sessionListener, stanzaListener)
  }

  "addContact" should "add a contact to the user's contact list" in testEnvironment { _ =>
    val expectedRosterPacket: RosterPacket = new RosterPacket
    expectedRosterPacket.setType(IQ.Type.set)
    val expectedRosterItem: RosterPacket.Item = new RosterPacket.Item(JidCreate.entityBareFrom(TestContact1), TestContact1)
    expectedRosterPacket.addRosterItem(expectedRosterItem)

    val (session, contactsConnection) = createSessionWithMockConnectionForContactTesting()
    val stanzaCollector = StanzaCollectorHelper.createStanzaCollector(contactsConnection)
    //Adding an arbitrary packet to make the test pass :/
    StanzaCollectorHelper.callProcessStanza(stanzaCollector, new Ping())
    when(contactsConnection.createStanzaCollectorAndSend(argThat(new IQPacketMatcher(expectedRosterPacket)))).thenReturn(
      stanzaCollector
    )
    Await.result(session.addContact(TestContact1), TestAwaitTimeout)

    verify(contactsConnection).createStanzaCollectorAndSend(argThat(new IQPacketMatcher(expectedRosterPacket)))
  }

  private def createSessionWithMockConnectionForContactTesting(): (SmackXMPPSession, XMPPConnection) = {
    //TODO this is horrid, see comment at top of file
    val contactsConnection = mock[XMPPConnection]
    when(contactsConnection.isAuthenticated).thenReturn(true)
    when(contactsConnection.isConnected).thenReturn(true)
    when(contactsConnection.getReplyTimeout).thenReturn(2000L)
    val stanzaFactory = new StanzaFactory(new StandardStanzaIdSource())
    when(contactsConnection.getStanzaFactory).thenReturn(stanzaFactory)

    // The Roster.reload method sends an empty RosterPacket and expects to get a future back
    when(contactsConnection.sendIqRequestAsync(any())).thenReturn(new SmackFuture[IQ, Exception] {})

    new SmackXMPPSession(contactsConnection, mock[XMPPSessionListener], TestXMPPSettings, global) -> contactsConnection
  }

  "contact added packet in underlying connection" should "trigger contactAdded event on session listener" in testEnvironment { environment =>
    //TODO I'm not very happy that we're testing against a presence type of Unknown here
    //but with our current testing approach it's tricky to set another value when the contact is being added

    createSessionWithUsername(environment.connection, environment.sessionListener)
    val requestHandlerCaptor: ArgumentCaptor[AbstractIqRequestHandler] = ArgumentCaptor.forClass(classOf[AbstractIqRequestHandler])
    verify(environment.connection, atLeastOnce()).registerIQRequestHandler(requestHandlerCaptor.capture())
    val contactListRequestHandler = getValueFromArgumentCaptor(requestHandlerCaptor, 0)

    sendContactAddedEventToHandler(contactListRequestHandler, TestContact1)
    verify(environment.sessionListener).contactsAdded(Vector(ContactInfo(TestContact1, tela.baseinterfaces.Presence.Unknown)))

    sendContactAddedEventToHandler(contactListRequestHandler, TestContact2)
    verify(environment.sessionListener).contactsAdded(Vector(ContactInfo(TestContact2, tela.baseinterfaces.Presence.Unknown)))
  }

  private def sendContactAddedEventToHandler(handler: AbstractIqRequestHandler, contact: String): Unit = {
    val contactAddedPacket = new RosterPacket()
    val item = new Item(JidCreate.entityBareFrom(contact), contact)
    item.setItemType(RosterPacket.ItemType.both)
    contactAddedPacket.addRosterItem(item)

    handler.handleIQRequest(contactAddedPacket)
  }

  "Subscription request" should "be accepted and also result in reciprocal request" in testEnvironment { environment =>
    createSessionWithUsername(environment.connection, environment.sessionListener)
    environment.connection.setAuthenticated()
    val packetListenerCaptor: ArgumentCaptor[StanzaListener] = ArgumentCaptor.forClass(classOf[StanzaListener])
    verify(environment.connection, atLeastOnce()).addAsyncStanzaListener(packetListenerCaptor.capture(), any[StanzaFilter]())

    val contact2Jid = JidCreate.bareFrom(TestContact2)

    val message = createSubscribePresencePacket()
    message.setFrom(contact2Jid)

    getValueFromArgumentCaptor(packetListenerCaptor, 0).processStanza(message)

    val reciprocalSubscriptionRequest = createSubscribePresencePacket()
    reciprocalSubscriptionRequest.setTo(contact2Jid)

    val subscriptionAcceptedResponse = createSubscribedPresencePacket()
    subscriptionAcceptedResponse.setTo(contact2Jid)

    verify(environment.connection).sendStanzaInternal(argThat(new PresenceMatcher(reciprocalSubscriptionRequest)))
    verify(environment.connection).sendStanzaInternal(argThat(new PresenceMatcher(subscriptionAcceptedResponse)))
  }

  "CallSignal" should "generate appropriate generate appropriate IQ packet of type 'get'" in testEnvironment { _ =>
    val callSignal = new CallSignal(TestCallSignalData)
    val packetAsXML: NodeSeq = XML.loadString(callSignal.toXML(null).toString)
    packetAsXML.asInstanceOf[Elem].label should === ("iq")

    // We explicitly set the type of the CallSignal to be set when we're actually sending it
    // Perhaps we should be making these packets type get by "default"
    packetAsXML.asInstanceOf[Elem].attributes.get("type").get.toString should === ("get")
    (packetAsXML \ "callSignal").toString() should === (RawTestCallSignalPacket)
  }

  "sendCallSignal" should "send CallSignal packet to underlying connection" in testEnvironment { environment =>
    val session = connectToServerAndGetSession(environment.connection, environment.sessionListener)
    Await.result(session.sendCallSignal(s"$TestBareJid/${SmackXMPPSession.DefaultResourceName}", TestCallSignalData), TestAwaitTimeout)
    val signal: CallSignal = createCallSignal(TestCallSignalData, s"$TestBareJid/${SmackXMPPSession.DefaultResourceName}", Type.set)
    verify(environment.connection).sendStanzaInternal(argThat(new IQPacketMatcher(signal)))
  }

  it should "append resource name if bare JID is supplied" in testEnvironment { environment =>
    val session = connectToServerAndGetSession(environment.connection, environment.sessionListener)
    Await.result(session.sendCallSignal(TestBareJid, TestCallSignalData), TestAwaitTimeout)
    val signal: CallSignal = createCallSignal(TestCallSignalData, s"$TestBareJid/${SmackXMPPSession.DefaultResourceName}", Type.set)
    verify(environment.connection).sendStanzaInternal(argThat(new IQPacketMatcher(signal)))
  }

  "call signal packet in underlying connection" should "trigger callSignalReceived event on session listener" in testEnvironment { environment =>
    connectToServer(environment.connection, environment.sessionListener)
    val requestHandlerCaptor: ArgumentCaptor[IQRequestHandler] = ArgumentCaptor.forClass(classOf[IQRequestHandler])
    verify(environment.connection, atLeastOnce()).registerIQRequestHandler(requestHandlerCaptor.capture())

    val iqHandler = requestHandlerCaptor.getValue
    iqHandler.getType should === (IQ.Type.set)
    iqHandler.getMode should === (IQRequestHandler.Mode.async)
    iqHandler.getNamespace should === (TelaURN)
    iqHandler.getElement should === (CallSignalElementName)

    val signal: CallSignal = createCallSignal(TestCallSignalData, s"$TestBareJid/${SmackXMPPSession.DefaultResourceName}", Type.set)
    signal.setFrom(JidCreate.bareFrom(TestBareJid))
    iqHandler.handleIQRequest(signal) should === (null)

    verify(environment.sessionListener).callSignalReceived(TestBareJid, TestCallSignalData)
  }

  "callSignalProvider" should "parse handle CallSignal IQ packets" in testEnvironment { _ =>
    val provider = ProviderManager.getIQProvider(SmackXMPPSession.CallSignalElementName, SmackXMPPSession.TelaURN)
    val xmlParser = PacketParserUtils.getParserFor(new StringReader(RawTestCallSignalPacket))
    new IQPacketMatcher(new CallSignal(TestCallSignalData)).matches(provider.parse(xmlParser, null)) should === (true)
  }

  "sendChatMessage" should "send appropriately formed chat message to underlying connection" in testEnvironment { environment =>
    val session = connectToServerAndGetSession(environment.connection, environment.sessionListener)
    Await.result(session.sendChatMessage(TestBareJid, TestChatMessage), TestAwaitTimeout)
    val message: Message = createChatMessageBuilder().setBody(TestChatMessage).to(TestBareJid).build()
    verify(environment.connection).sendStanzaInternal(argThat(new ChatMessagePacketMatcher(message)))
  }

  "chat message received by underlying connection" should "trigger chatMessageReceived in session listener" in testEnvironment { environment =>
    connectToServer(environment.connection, environment.sessionListener)
    val packetListenerCaptor: ArgumentCaptor[StanzaListener] = ArgumentCaptor.forClass(classOf[StanzaListener])
    verify(environment.connection, atLeastOnce()).addSyncStanzaListener(packetListenerCaptor.capture(), any[StanzaFilter]())

    val message = createChatMessageBuilder().setBody(TestChatMessage).from(TestFullJid).build()

    // We use the index 1 because the roster listener gets added first, then the chat listener (in accordance with our connectToServer method)
    getValueFromArgumentCaptor(packetListenerCaptor, 1).processStanza(message)
    verify(environment.sessionListener, timeout(AsyncWaitTimeoutMs)).chatMessageReceived(TestBareJid, TestChatMessage)
  }

  private def assertThatReceiptOfPresenceChangeForContact1IsHandledCorrectly(expectedResult: tela.baseinterfaces.Presence,
                                                                  xmppType: Presence.Type,
                                                                  xmppMode: Presence.Mode,
                                                                  sessionListener: XMPPSessionListener,
                                                                  stanzaListener: StanzaListener): Unit = {
    val presence = createSmackPresence(xmppType, xmppMode)
    presence.setFrom(JidCreate.bareFrom(TestContact1))
    reset(sessionListener) //Need to do this to ensure that old calls with same expected values don't cause failures
    stanzaListener.processStanza(presence)
    verify(sessionListener, timeout(AsyncWaitTimeoutMs)).presenceChanged(ContactInfo(TestContact1, expectedResult))
  }

  private def getValueFromArgumentCaptor[T](captor: ArgumentCaptor[T], index: Int): T = {
    captor.getAllValues.asScala(index)
  }

  private def createStanzaFactory() = new StanzaFactory(StandardStanzaIdSource.DEFAULT)

  private def createSmackPresence(status: Mode): Presence =
    createSmackPresence(Presence.Type.available, status)

  private def createSmackPresence(presenceType: Presence.Type, status: Mode): Presence =
    createStanzaFactory().buildPresenceStanza().ofType(presenceType).setMode(status).
      setStatus(DefaultStatusText).setPriority(DefaultPriority).build()

  private def createSubscribePresencePacket() =
    createStanzaFactory().buildPresenceStanza().ofType(Presence.Type.subscribe).build()

  private def createSubscribedPresencePacket() =
    createStanzaFactory().buildPresenceStanza().ofType(Presence.Type.subscribed).build()

  private def createChatMessageBuilder() = {
    createStanzaFactory().buildMessageStanza()
  }

  private def createSessionWithUsername(connection: TestableXMPPConnection, sessionListener: XMPPSessionListener): XMPPSession = {
    val session = connectToServerAndGetSession(connection, sessionListener)
    when(connection.setUser(TestFullJid)).thenCallRealMethod()
    connection.setUser(TestFullJid)
    when(connection.getUser).thenCallRealMethod()
    session
  }

  private def createCallSignal(data: String, to: String, iqType: Type): CallSignal = {
    val result = new CallSignal(data)
    result.setTo(JidCreate.entityFullFrom(to))
    result.setType(iqType)
    result
  }

  private class TestableXMPPConnection extends AbstractXMPPConnection(
    XMPPTCPConnectionConfiguration.builder().setXmppDomain(TestDomain).setSecurityMode(SecurityMode.disabled).build()
  ) {
    def setUser(username: String): Unit = {
      user = JidCreate.entityFullFrom(username)
    }

    def setAuthenticated(): Unit = {
      authenticated = true
    }

    override def isSecureConnection: Boolean = false

    override def sendStanzaInternal(packet: Stanza): Unit = {}

    override def sendNonza(element: Nonza): Unit = {}

    override def isUsingCompression: Boolean = false

    override def connectInternal(): Unit = {
      connected = true
    }

    override def loginInternal(username: String, password: String, resource: Resourcepart): Unit = {}

    override def instantShutdown(): Unit = {}

    override def shutdown(): Unit = {}
  }

  private def connectToServer(connection: TestableXMPPConnection, sessionListener: XMPPSessionListener): Either[LoginFailure, XMPPSession] = {
    Await.result(SmackXMPPSession.connectToServer(TestUsername, TestPassword, connection, TestXMPPSettings, sessionListener, global), TestAwaitTimeout)
  }

  private def connectToServerAndGetSession(connection: TestableXMPPConnection, sessionListener: XMPPSessionListener): XMPPSession = {
    connectToServer(connection, sessionListener).toOption.value
  }

  private def verifyPresencePacketSent(connection: TestableXMPPConnection, presence: Presence): Unit = {
    verify(connection).sendStanzaInternal(argThat(new PresenceMatcher(presence)))
  }

  private class ChatMessagePacketMatcher(private val expected: Message) extends ArgumentMatcher[Message] {
    override def matches(actual: Message): Boolean = expected.getTo == actual.getTo && expected.getBody == actual.getBody
  }

  private class IQPacketMatcher(private val expected: IQ) extends ArgumentMatcher[IQ] {
    override def matches(actual: IQ): Boolean =
      expected.getTo == actual.getTo &&
        expected.getType == actual.getType &&
        XML.loadString(expected.getChildElementXML.toString) == XML.loadString(actual.getChildElementXML.toString)
  }

  private class RegistrationMatcher(private val expected: Registration) extends ArgumentMatcher[Registration] {
    override def matches(actual: Registration): Boolean =
      expected.getType == actual.getType && expected.getAttributes.asScala == actual.getAttributes.asScala
  }

  private class PresenceMatcher(private val expected: Presence) extends ArgumentMatcher[Presence] {
    override def matches(actual: Presence): Boolean =
      expected.getType == actual.getType &&
        expected.getMode == actual.getMode &&
        expected.getStatus == actual.getStatus &&
        expected.getPriority == actual.getPriority &&
        expected.getFrom == actual.getFrom &&
        expected.getTo == actual.getTo
  }
}
