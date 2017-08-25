package tela.xmpp

import java.io.StringReader

import org.jivesoftware.smack.SmackException.ConnectionException
import org.jivesoftware.smack._
import org.jivesoftware.smack.filter.StanzaFilter
import org.jivesoftware.smack.iqrequest.AbstractIqRequestHandler
import org.jivesoftware.smack.packet.IQ.Type
import org.jivesoftware.smack.packet.Presence.Mode
import org.jivesoftware.smack.packet.{Presence, _}
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.roster.Roster
import org.jivesoftware.smack.roster.Roster.SubscriptionMode
import org.jivesoftware.smack.roster.packet.RosterPacket
import org.jivesoftware.smack.roster.packet.RosterPacket.Item
import org.jivesoftware.smack.sasl.packet.SaslStreamElements.SASLFailure
import org.jivesoftware.smack.sasl.{SASLError, SASLErrorException}
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smackx.commands.packet.AdHocCommandData
import org.jivesoftware.smackx.iqregister.packet.Registration
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.mockito.{ArgumentCaptor, ArgumentMatcher}
import org.scalatest.Matchers._
import tela.baseinterfaces._
import tela.xmpp.SmackXMPPSession.CallSignal

import scala.xml.{Elem, NodeSeq, XML}

//TODO Smack seems to be getting harder to test with each successive version.
//Using PowerMockito to more effectively mock AbstractXMPPConnection may be the most practical solution
class SmackXMPPSessionSpec extends BaseSpec {
  private val SmackUsernameKey = "username"
  private val SmackPasswordKey = "password"

  private val TestResource = "res"
  private val TestBareJid = TestUsername + "@" + TestDomain
  private val TestFullJid = TestBareJid + "/" + TestResource
  //TODO Reference constant for callSignal element name
  private val RawTestCallSignalPacket = <callSignal xmlns={SmackXMPPSession.TelaURN}>{TestCallSignalData}</callSignal>.toString()

  private class TestEnvironment(val connection: TestableXMPPConnection, val sessionListener: XMPPSessionListener)

  private def testEnvironment(runTest: (TestEnvironment) => Unit): Unit = {
    runTest(new TestEnvironment(mock[TestableXMPPConnection], mock[XMPPSessionListener]))
  }

  "connectToServer" should "return a session object when valid credentials are supplied" in testEnvironment { environment =>
    val session = connectToServer(environment.connection, environment.sessionListener)
    session.isRight should === (true)
    Roster.getInstanceFor(environment.connection).getSubscriptionMode should === (SubscriptionMode.accept_all)
    verify(environment.connection).connect()
    verify(environment.connection).login(TestUsername, TestPassword, Resourcepart.from(SmackXMPPSession.DefaultResourceName))
  }

  it should "return an appropriate error and disconnect underlying XMPP connection when invalid credentials are supplied" in testEnvironment { environment =>
    when(environment.connection.login(TestUsername, TestPassword, Resourcepart.from(SmackXMPPSession.DefaultResourceName))).thenThrow(
      new SASLErrorException("", new SASLFailure(SASLError.not_authorized.toString))
    )
    connectToServer(environment.connection, environment.sessionListener) should === (Left(LoginFailure.InvalidCredentials))
    verify(environment.connection).disconnect()
  }

  it should "return an appropriate error and disconnect underlying XMPP connection when connection fails" in testEnvironment { environment =>
    when(environment.connection.connect()).thenThrow(new ConnectionException(new Exception()))
    connectToServer(environment.connection, environment.sessionListener) should === (Left(LoginFailure.ConnectionFailure))
    verify(environment.connection).disconnect()
  }

  "disconnect" should "disconnect underlying session" in testEnvironment { environment =>
    connectToServerAndGetSession(environment.connection, environment.sessionListener).disconnect()
    verify(environment.connection).disconnect()
  }

  "setPresence" should "set presence to available when requested" in testEnvironment { environment =>
    val session = connectToServerAndGetSession(environment.connection, environment.sessionListener)
    session.setPresence(tela.baseinterfaces.Presence.Available)
    verifyPresencePacketSent(environment.connection, createSmackPresence(Presence.Mode.available))
  }

  it should "set presence to away when requested" in testEnvironment { environment =>
    val session = connectToServerAndGetSession(environment.connection, environment.sessionListener)
    session.setPresence(tela.baseinterfaces.Presence.Away)
    verifyPresencePacketSent(environment.connection, createSmackPresence(Presence.Mode.away))
  }

  it should "set presence to DND when requested" in testEnvironment { environment =>
    val session = connectToServerAndGetSession(environment.connection, environment.sessionListener)
    session.setPresence(tela.baseinterfaces.Presence.DoNotDisturb)
    verifyPresencePacketSent(environment.connection, createSmackPresence(Presence.Mode.dnd))
  }

  it should "ignore request to set presence to Unknown" in testEnvironment { environment =>
    val session = connectToServerAndGetSession(environment.connection, environment.sessionListener)
    session.setPresence(tela.baseinterfaces.Presence.Unknown)
    verify(environment.connection, never()).sendStanza(any())
  }

  private def createSmackPresence(status: Mode) = new Presence(Presence.Type.available, SmackXMPPSession.DefaultStatusText, SmackXMPPSession.DefaultPriority, status)

  "changePassword" should "create a second connection to test old password and then use it to change the password" in testEnvironment { environment =>
    val session: XMPPSession = createSessionWithUsername(environment.connection, environment.sessionListener)

    val changePasswordConnection = mock[TestableXMPPConnection]
    when(changePasswordConnection.setUser(TestFullJid)).thenCallRealMethod()
    changePasswordConnection.setUser(TestFullJid)
    when(changePasswordConnection.getUser).thenCallRealMethod()

    import scala.collection.JavaConversions._
    val expectedRegistrationPacket: Registration = new Registration(Map(SmackUsernameKey -> TestUsername, SmackPasswordKey -> TestNewPassword))
    expectedRegistrationPacket.setType(IQ.Type.set)

    val packetCollector = mock[StanzaCollector]
    when(changePasswordConnection.createStanzaCollectorAndSend(isA(classOf[StanzaFilter]), argThat(new RegistrationMatcher(expectedRegistrationPacket)))).thenReturn(packetCollector)

    session.asInstanceOf[SmackXMPPSession].changePassword(TestPassword, TestNewPassword, changePasswordConnection) should === (true)

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

    session.asInstanceOf[SmackXMPPSession].changePassword(TestPassword, TestNewPassword, changePasswordConnection) should === (false)
    verify(changePasswordConnection).disconnect()
  }

  it should "not change password and return false if new password is empty string" in testEnvironment { environment =>
    val session = connectToServerAndGetSession(environment.connection, environment.sessionListener)
    val changePasswordConnection = mock[TestableXMPPConnection]
    session.asInstanceOf[SmackXMPPSession].changePassword(TestPassword, "", changePasswordConnection) should === (false)
    verify(changePasswordConnection).disconnect()
  }

  "getContactList" should "send the user's contacts to the session listener" in testEnvironment { environment =>
    val session = createSessionWithUsername(environment.connection, environment.sessionListener)

    val argument: ArgumentCaptor[AbstractIqRequestHandler] = ArgumentCaptor.forClass(classOf[AbstractIqRequestHandler])
    verify(environment.connection).registerIQRequestHandler(argument.capture())

    val packetListenerCaptor: ArgumentCaptor[StanzaListener] = ArgumentCaptor.forClass(classOf[StanzaListener])
    verify(environment.connection, atLeastOnce()).addSyncStanzaListener(packetListenerCaptor.capture(), any[StanzaFilter]())
    // We use the index 0 because the roster listener gets added first, then the chat listener (in accordance with our connectToServer method)
    val stanzaListener = getValueFromArgumentCaptor(packetListenerCaptor, 0)

    sendContactAddedEventToHandler(argument.getValue, TestContact1)
    val presence: Presence = new Presence(Presence.Type.available, "", 0, Presence.Mode.away)
    presence.setFrom(JidCreate.bareFrom(TestContact1))
    stanzaListener.processStanza(presence)

    sendContactAddedEventToHandler(argument.getValue, TestContact2)
    presence.setFrom(JidCreate.bareFrom(TestContact2))
    stanzaListener.processStanza(presence)

    reset(environment.sessionListener) //Need to do this to ensure the calls to add the contacts in the first place don't interfere with results

    session.getContactList()

    //It seems that the order that the contacts come back in depends on the precise content of the JIDs. Changing the values of the test contacts may change the order
    verify(environment.sessionListener).contactsAdded(List(ContactInfo(TestContact2, tela.baseinterfaces.Presence.Away), ContactInfo(TestContact1, tela.baseinterfaces.Presence.Away)))
  }

  "presenceChanged" should "set the user's presence to the given value" in testEnvironment { environment =>
    createSessionWithUsername(environment.connection, environment.sessionListener)

    val packetListenerCaptor: ArgumentCaptor[StanzaListener] = ArgumentCaptor.forClass(classOf[StanzaListener])
    verify(environment.connection, atLeastOnce()).addSyncStanzaListener(packetListenerCaptor.capture(), any[StanzaFilter]())
    // We use the index 0 because the roster listener gets added first, then the chat listener (in accordance with our connectToServer method)
    val stanzaListener = getValueFromArgumentCaptor(packetListenerCaptor, 0)

    val argument: ArgumentCaptor[AbstractIqRequestHandler] = ArgumentCaptor.forClass(classOf[AbstractIqRequestHandler])
    verify(environment.connection).registerIQRequestHandler(argument.capture())

    val contactAddedPacket1 = new RosterPacket()
    val item1 = new Item(JidCreate.entityBareFrom(TestContact1), TestContact1) //TODO This is duplicated in assertThatReceiptOfPresenceChangeForContact1IsHandledCorrectly
    item1.setItemType(RosterPacket.ItemType.both)
    contactAddedPacket1.addRosterItem(item1)

    argument.getValue.handleIQRequest(contactAddedPacket1)

    assertThatReceiptOfPresenceChangeForContact1IsHandledCorrectly(tela.baseinterfaces.Presence.Unknown, Presence.Type.unavailable, Presence.Mode.available, environment.sessionListener, stanzaListener)
    assertThatReceiptOfPresenceChangeForContact1IsHandledCorrectly(tela.baseinterfaces.Presence.Available, Presence.Type.available, Presence.Mode.available, environment.sessionListener, stanzaListener)
    assertThatReceiptOfPresenceChangeForContact1IsHandledCorrectly(tela.baseinterfaces.Presence.Away, Presence.Type.available, Presence.Mode.away, environment.sessionListener, stanzaListener)
    assertThatReceiptOfPresenceChangeForContact1IsHandledCorrectly(tela.baseinterfaces.Presence.Available, Presence.Type.available, Presence.Mode.chat, environment.sessionListener, stanzaListener)
    assertThatReceiptOfPresenceChangeForContact1IsHandledCorrectly(tela.baseinterfaces.Presence.DoNotDisturb, Presence.Type.available, Presence.Mode.dnd, environment.sessionListener, stanzaListener)
    assertThatReceiptOfPresenceChangeForContact1IsHandledCorrectly(tela.baseinterfaces.Presence.Away, Presence.Type.available, Presence.Mode.xa, environment.sessionListener, stanzaListener)
    assertThatReceiptOfPresenceChangeForContact1IsHandledCorrectly(tela.baseinterfaces.Presence.Available, Presence.Type.available, null, environment.sessionListener, stanzaListener)
  }

  "addContact" should "add a contact to the user's contact list" in testEnvironment { environment =>
    val expectedRosterPacket: RosterPacket = new RosterPacket
    expectedRosterPacket.setType(IQ.Type.set)
    val expectedRosterItem: RosterPacket.Item = new RosterPacket.Item(JidCreate.entityBareFrom(TestContact1), TestContact1)
    expectedRosterPacket.addRosterItem(expectedRosterItem)

    val (session, contactsConnection) = createSessionWithMockConnectionForContactTesting()
    when(contactsConnection.createStanzaCollectorAndSend(argThat(new IQPacketMatcher(expectedRosterPacket)))).thenReturn(new PacketCollectorImpl(contactsConnection))
    session.addContact(TestContact1)

    verify(contactsConnection).createStanzaCollectorAndSend(argThat(new IQPacketMatcher(expectedRosterPacket)))
  }

  private def createSessionWithMockConnectionForContactTesting(): (SmackXMPPSession, XMPPConnection) = {
    //TODO this is horrid, see comment at top of file
    val contactsConnection = mock[XMPPConnection]
    when(contactsConnection.isAuthenticated).thenReturn(true)
    when(contactsConnection.isAnonymous).thenReturn(false)
    new SmackXMPPSession(contactsConnection, mock[XMPPSessionListener], TestXMPPSettings) -> contactsConnection
  }

  private class PacketCollectorImpl(connection: XMPPConnection) extends StanzaCollector(connection, StanzaCollector.newConfiguration()) {
    override def nextResultOrThrow[P <: Stanza](): P = {
      null.asInstanceOf[P]
    }
  }

  "contact added packet in underlying connection" should "trigger contactAdded event on session listener" in testEnvironment { environment =>
    //TODO I'm not very happy that we're testing against a presence type of Unknown here
    //but with our current testing approach it's tricky to set another value when the contact is being added

    createSessionWithUsername(environment.connection, environment.sessionListener)
    val argument: ArgumentCaptor[AbstractIqRequestHandler] = ArgumentCaptor.forClass(classOf[AbstractIqRequestHandler])
    verify(environment.connection).registerIQRequestHandler(argument.capture())

    sendContactAddedEventToHandler(argument.getValue, TestContact1)
    verify(environment.sessionListener).contactsAdded(List(ContactInfo(TestContact1, tela.baseinterfaces.Presence.Unknown)))

    sendContactAddedEventToHandler(argument.getValue, TestContact2)
    verify(environment.sessionListener).contactsAdded(List(ContactInfo(TestContact2, tela.baseinterfaces.Presence.Unknown)))
  }

  private def sendContactAddedEventToHandler(handler: AbstractIqRequestHandler, contact: String): Unit = {
    val contactAddedPacket = new RosterPacket()
    val item = new Item(JidCreate.entityBareFrom(contact), contact)
    item.setItemType(RosterPacket.ItemType.both)
    contactAddedPacket.addRosterItem(item)

    handler.handleIQRequest(contactAddedPacket)
  }

  "CallSignal" should "generate appropriate generate appropriate IQ packet of type 'get'" in testEnvironment { environment =>
    val callSignal = new CallSignal(TestCallSignalData)
    val packetAsXML: NodeSeq = XML.loadString(callSignal.toXML.toString)
    packetAsXML.asInstanceOf[Elem].label should === ("iq")

    // We explicitly set the type of the CallSignal to be set when we're actually sending it
    // Perhaps we should be making these packets type get by "default"
    packetAsXML.asInstanceOf[Elem].attributes.get("type").get.toString should === ("get")
    (packetAsXML \ "callSignal").toString() should === (RawTestCallSignalPacket)
  }

  "sendCallSignal" should "send CallSignal packet to underlying connection" in testEnvironment { environment =>
    val session = connectToServerAndGetSession(environment.connection, environment.sessionListener)
    session.sendCallSignal(TestBareJid + "/" + SmackXMPPSession.DefaultResourceName, TestCallSignalData)
    val signal: CallSignal = createCallSignal(TestCallSignalData, TestBareJid + "/" + SmackXMPPSession.DefaultResourceName, Type.set)
    verify(environment.connection).sendStanza(argThat(new IQPacketMatcher(signal)))
  }

  it should "append resource name if bare JID is supplied" in testEnvironment { environment =>
    val session = connectToServerAndGetSession(environment.connection, environment.sessionListener)
    session.sendCallSignal(TestBareJid, TestCallSignalData)
    val signal: CallSignal = createCallSignal(TestCallSignalData, TestBareJid + "/" + SmackXMPPSession.DefaultResourceName, Type.set)
    verify(environment.connection).sendStanza(argThat(new IQPacketMatcher(signal)))
  }

  "call signal packet in underlying connection" should "trigger callSignalReceived event on session listener" in testEnvironment { environment =>
    connectToServer(environment.connection, environment.sessionListener)
    val packetListenerCaptor: ArgumentCaptor[StanzaListener] = ArgumentCaptor.forClass(classOf[StanzaListener])
    val packetFilterCaptor: ArgumentCaptor[StanzaFilter] = ArgumentCaptor.forClass(classOf[StanzaFilter])
    verify(environment.connection, atLeastOnce()).addAsyncStanzaListener(packetListenerCaptor.capture(), packetFilterCaptor.capture())

    val signal: CallSignal = createCallSignal(TestCallSignalData, TestBareJid + "/" + SmackXMPPSession.DefaultResourceName, Type.set)

    // We use the index 0 because we expect the listener to be the second one added (the first one is for presence).
    getValueFromArgumentCaptor(packetFilterCaptor, 1).accept(signal) should === (true)

    //verifying that our filter doesn't accept just a packet that isn't a call signal
    packetFilterCaptor.getValue.accept(new AdHocCommandData) should === (false)

    signal.setFrom(JidCreate.bareFrom(TestBareJid))
    getValueFromArgumentCaptor(packetListenerCaptor, 1).processStanza(signal)

    verify(environment.sessionListener).callSignalReceived(TestBareJid, TestCallSignalData)
  }

  "callSignalProvider" should "parse handle CallSignal IQ packets" in testEnvironment { environment =>
    val provider = ProviderManager.getIQProvider(SmackXMPPSession.CallSignalElementName, SmackXMPPSession.TelaURN)
    val xmlParser = PacketParserUtils.newXmppParser()
    xmlParser.setInput(new StringReader(RawTestCallSignalPacket))
    xmlParser.nextToken() //simulates the state of the parser when it gets passed to the provider in production
    new IQPacketMatcher(new CallSignal(TestCallSignalData)).matches(provider.parse(xmlParser)) should === (true)
  }

  "sendChatMessage" should "send appropriately formed chat message to underlying connection" in testEnvironment { environment =>
    val session = connectToServerAndGetSession(environment.connection, environment.sessionListener)
    session.sendChatMessage(TestBareJid, TestChatMessage)
    val message: Message = new Message(JidCreate.from(TestBareJid))
    message.setBody(TestChatMessage)
    verify(environment.connection).sendStanza(argThat(new ChatMessagePacketMatcher(message)))
  }

  "chat message received by underlying connection" should "trigger chatMessageReceived in session listener" in testEnvironment { environment =>
    connectToServer(environment.connection, environment.sessionListener)
    val packetListenerCaptor: ArgumentCaptor[StanzaListener] = ArgumentCaptor.forClass(classOf[StanzaListener])
    verify(environment.connection, atLeastOnce()).addSyncStanzaListener(packetListenerCaptor.capture(), any[StanzaFilter]())

    val message = new Message()
    message.setFrom(JidCreate.from(TestFullJid))
    message.setBody(TestChatMessage)

    // We use the index 1 because the roster listener gets added first, then the chat listener (in accordance with our connectToServer method)
    getValueFromArgumentCaptor(packetListenerCaptor, 1).processStanza(message)
    verify(environment.sessionListener).chatMessageReceived(TestBareJid, TestChatMessage)
  }

  private def assertThatReceiptOfPresenceChangeForContact1IsHandledCorrectly(expectedResult: tela.baseinterfaces.Presence,
                                                                  xmppType: Presence.Type,
                                                                  xmppMode: Presence.Mode,
                                                                  sessionListener: XMPPSessionListener,
                                                                  stanzaListener: StanzaListener): Unit = {
    val presence: Presence = new Presence(xmppType, "", 0, xmppMode)
    presence.setFrom(JidCreate.bareFrom(TestContact1))
    reset(sessionListener) //Need to do this to ensure that old calls with same expected values don't cause failures
    stanzaListener.processStanza(presence)
    verify(sessionListener).presenceChanged(ContactInfo(TestContact1, expectedResult))
  }

  private def getValueFromArgumentCaptor[T](captor: ArgumentCaptor[T], index: Int): T = {
    import scala.collection.JavaConversions._
    captor.getAllValues.toList(index)
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

  private abstract class TestableXMPPConnection extends AbstractXMPPConnection(XMPPTCPConnectionConfiguration.builder().build()) {
    def setUser(username: String): Unit = {
      user = JidCreate.entityFullFrom(username)
    }
  }

  private def connectToServer(connection: TestableXMPPConnection, sessionListener: XMPPSessionListener): Either[LoginFailure, XMPPSession] = {
    SmackXMPPSession.connectToServer(TestUsername, TestPassword, connection, TestXMPPSettings, sessionListener)
  }

  private def connectToServerAndGetSession(connection: TestableXMPPConnection, sessionListener: XMPPSessionListener): XMPPSession = {
    connectToServer(connection, sessionListener).right.get
  }

  private def verifyPresencePacketSent(connection: TestableXMPPConnection, presence: Presence): Unit = {
    verify(connection).sendStanza(argThat(new PresenceMatcher(presence)))
  }

  private class ChatMessagePacketMatcher(private val expected: Message) extends ArgumentMatcher[Message] {
    override def matches(argument: Message): Boolean = {
      argument match {
        case actual: Message =>
          expected.getTo == actual.getTo && expected.getBody == actual.getBody
        case _ => false
      }
    }
  }

  private class IQPacketMatcher(private val expected: IQ) extends ArgumentMatcher[IQ] {
    override def matches(argument: IQ): Boolean = argument match {
      case actual: IQ =>
        expected.getTo == actual.getTo && expected.getType == actual.getType &&
          XML.loadString(expected.getChildElementXML.toString) == XML.loadString(actual.getChildElementXML.toString)
      case _ => false
    }
  }

  private class RegistrationMatcher(private val expected: Registration) extends ArgumentMatcher[Registration] {
    import scala.collection.JavaConversions._
    override def matches(argument: Registration): Boolean = argument match {
        case actual: Registration =>
          expected.getType == actual.getType && mapAsScalaMap(expected.getAttributes) == mapAsScalaMap(actual.getAttributes)
        case _ => false
    }
  }

  private class PresenceMatcher(private val expected: Presence) extends ArgumentMatcher[Presence] {
    override def matches(argument: Presence): Boolean = argument match {
      case actual: Presence => expected.getType == actual.getType &&
        expected.getMode == actual.getMode &&
        expected.getStatus == actual.getStatus &&
        expected.getPriority == actual.getPriority
      case _ => false
    }
  }
}
