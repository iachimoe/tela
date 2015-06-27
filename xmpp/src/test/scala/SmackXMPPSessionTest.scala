package tela.xmpp

import java.io.StringReader

import org.jivesoftware.smack.SmackException.ConnectionException
import org.jivesoftware.smack._
import org.jivesoftware.smack.filter.StanzaFilter
import org.jivesoftware.smack.iqrequest.AbstractIqRequestHandler
import org.jivesoftware.smack.packet.IQ.Type
import org.jivesoftware.smack.packet.{Presence, _}
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.roster.Roster
import org.jivesoftware.smack.roster.packet.RosterPacket
import org.jivesoftware.smack.roster.packet.RosterPacket.Item
import org.jivesoftware.smack.sasl.packet.SaslStreamElements.SASLFailure
import org.jivesoftware.smack.sasl.{SASLError, SASLErrorException}
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smackx.commands.packet.AdHocCommandData
import org.jivesoftware.smackx.iqregister.packet.Registration
import org.junit.Assert._
import org.junit.{Before, Test}
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.mockito.{ArgumentCaptor, ArgumentMatcher}
import org.scalatest.junit.AssertionsForJUnit
import org.scalatest.mock.MockitoSugar
import tela.baseinterfaces._
import tela.xmpp.SmackXMPPSession.CallSignal

import scala.collection.JavaConversions._
import scala.xml.{Elem, NodeSeq, XML}

//TODO Smack seems to be getting harder to test with each successive version.
//Using PowerMockito to more effectively mock AbstractXMPPConnection may be the most practical solution
class SmackXMPPSessionTest extends AssertionsForJUnit with MockitoSugar {
  private val SmackUsernameKey = "username"
  private val SmackPasswordKey = "password"

  private val TestUsername = "user"
  private val TestPassword = "pass"
  private val TestDomain = "example.com"
  private val TestResource = "res"
  private val TestBareJid = TestUsername + "@" + TestDomain
  private val TestFullJid = TestBareJid + "/" + TestResource
  private val TestCallSignalData = "test data"
  private val RawTestCallSignalPacket = <callSignal xmlns={SmackXMPPSession.TelaURN}>{TestCallSignalData}</callSignal>.toString()
  //TODO Reference constant for callSignal element name
  private val TestChatMessageData = "Chat message"

  private var connection: TestableXMPPConnection = null
  private var roster: Roster = null
  private var sessionListener: XMPPSessionListener = null

  private var contactsConnection: XMPPConnection = null

  @Before def initialize(): Unit = {
    connection = mock[TestableXMPPConnection]
    sessionListener = mock[XMPPSessionListener]
  }

  @Test def successfulLogin(): Unit = {
    val session = connectToServer
    assertTrue(session.isRight)
    verify(connection).connect()
    verify(connection).login(TestUsername, TestPassword, SmackXMPPSession.DefaultResourceName)
  }

  @Test def invalidCredentials(): Unit = {
    when(connection.login(TestUsername, TestPassword, SmackXMPPSession.DefaultResourceName)).thenThrow(new SASLErrorException("", new SASLFailure(SASLError.not_authorized.toString)))
    assertEquals(LoginFailure.InvalidCredentials, connectToServer.left.get)
    verify(connection).disconnect()
  }

  @Test def connectionFailed(): Unit = {
    when(connection.connect()).thenThrow(new ConnectionException(new Exception()))
    val session = connectToServer
    assertEquals(LoginFailure.ConnectionFailure, session.left.get)
    verify(connection).disconnect()
  }

  @Test def disconnect(): Unit = {
    connectToServerAndGetSession.disconnect()
    verify(connection).disconnect()
  }

  @Test def setPresenceToAvailable(): Unit = {
    val session = connectToServerAndGetSession
    session.setPresence(tela.baseinterfaces.Presence.Available)
    verifyPresencePacketSent(new Presence(Presence.Type.available, SmackXMPPSession.DefaultStatusText, SmackXMPPSession.DefaultPriority, Presence.Mode.available))
  }

  @Test def setPresenceToAway(): Unit = {
    val session = connectToServerAndGetSession
    session.setPresence(tela.baseinterfaces.Presence.Away)
    verifyPresencePacketSent(new Presence(Presence.Type.available, SmackXMPPSession.DefaultStatusText, SmackXMPPSession.DefaultPriority, Presence.Mode.away))
  }

  @Test def setPresenceToDND(): Unit = {
    val session = connectToServerAndGetSession
    session.setPresence(tela.baseinterfaces.Presence.DoNotDisturb)
    verifyPresencePacketSent(new Presence(Presence.Type.available, SmackXMPPSession.DefaultStatusText, SmackXMPPSession.DefaultPriority, Presence.Mode.dnd))
  }

  @Test def changePassword(): Unit = {
    val session: XMPPSession = createSessionWithUsername()

    val changePasswordConnection = mock[TestableXMPPConnection]
    when(changePasswordConnection.setUser(TestFullJid)).thenCallRealMethod()
    changePasswordConnection.setUser(TestFullJid)
    when(changePasswordConnection.getUser).thenCallRealMethod()

    val expectedRegistrationPacket: Registration = new Registration(scala.collection.JavaConversions.mapAsJavaMap(Map(SmackUsernameKey -> TestUsername, SmackPasswordKey -> "newPassword")))
    expectedRegistrationPacket.setType(IQ.Type.set)

    val packetCollector = mock[PacketCollector]
    when(changePasswordConnection.createPacketCollectorAndSend(isA(classOf[StanzaFilter]), argThat(new RegistrationMatcher(expectedRegistrationPacket)))).thenReturn(packetCollector)

    assertTrue(session.asInstanceOf[SmackXMPPSession].changePassword(TestPassword, "newPassword", changePasswordConnection))

    verify(changePasswordConnection).connect()
    verify(changePasswordConnection).login(TestUsername, TestPassword, SmackXMPPSession.ChangePasswordResource)
    verify(changePasswordConnection).createPacketCollectorAndSend(isA(classOf[StanzaFilter]), argThat(new RegistrationMatcher(expectedRegistrationPacket)))
    verify(changePasswordConnection).disconnect()
  }

  @Test def changePassword_incorrectOldPassword(): Unit = {
    val session: XMPPSession = createSessionWithUsername()

    val changePasswordConnection = mock[TestableXMPPConnection]
    when(changePasswordConnection.login(TestUsername, "wrongPassword", SmackXMPPSession.ChangePasswordResource)).thenThrow(new SASLErrorException("", new SASLFailure(SASLError.not_authorized.toString)))

    assertFalse(session.asInstanceOf[SmackXMPPSession].changePassword("wrongPassword", "newPassword", changePasswordConnection))
    verify(changePasswordConnection).disconnect()
  }

  @Test def changePassword_invalidNewPassword(): Unit = {
    val session = connectToServerAndGetSession
    val changePasswordConnection = mock[AbstractXMPPConnection]
    assertFalse(session.asInstanceOf[SmackXMPPSession].changePassword(TestPassword, "", changePasswordConnection))
    verify(changePasswordConnection).disconnect()
  }

  @Test def getContactList(): Unit = {
    val session = createSessionWithUsername()

    val argument: ArgumentCaptor[AbstractIqRequestHandler] = ArgumentCaptor.forClass(classOf[AbstractIqRequestHandler])
    verify(connection).registerIQRequestHandler(argument.capture())


    val packetListenerCaptor: ArgumentCaptor[StanzaListener] = ArgumentCaptor.forClass(classOf[StanzaListener])
    verify(connection, atLeastOnce()).addSyncStanzaListener(packetListenerCaptor.capture(), anyObject[StanzaFilter]())
    // We use the index 0 because the roster listener gets added first, then the chat listener (in accordance with our connectToServer method)
    val stanzaListener = getValueFromArgumentCaptor(packetListenerCaptor, 0)

    sendContactAddedEventToHandler(argument.getValue, "friend1@domain")
    val presence: Presence = new Presence(Presence.Type.available, "", 0, Presence.Mode.away)
    presence.setFrom("friend1@domain")
    stanzaListener.processPacket(presence)

    sendContactAddedEventToHandler(argument.getValue, "friend2@domain")
    presence.setFrom("friend2@domain")
    stanzaListener.processPacket(presence)

    reset(sessionListener) //Need to do this to ensure the calls to add the contacts in the first place don't interfere with results

    session.getContactList()

    verify(sessionListener).contactsAdded(List(ContactInfo("friend1@domain", tela.baseinterfaces.Presence.Away), ContactInfo("friend2@domain", tela.baseinterfaces.Presence.Away)))
  }

  @Test def presenceChanged(): Unit = {
    createSessionWithUsername()

    val packetListenerCaptor: ArgumentCaptor[StanzaListener] = ArgumentCaptor.forClass(classOf[StanzaListener])
    verify(connection, atLeastOnce()).addSyncStanzaListener(packetListenerCaptor.capture(), anyObject[StanzaFilter]())
    // We use the index 0 because the roster listener gets added first, then the chat listener (in accordance with our connectToServer method)
    val stanzaListener = getValueFromArgumentCaptor(packetListenerCaptor, 0)

    val argument: ArgumentCaptor[AbstractIqRequestHandler] = ArgumentCaptor.forClass(classOf[AbstractIqRequestHandler])
    verify(connection).registerIQRequestHandler(argument.capture())

    val contactAddedPacket1 = new RosterPacket()
    val item1 = new Item("foo@bar.net", "foo@bar.net") //TODO This is duplicated in assertThatReceiptOfPresenceChangeIsHandledCorrectly
    item1.setItemType(RosterPacket.ItemType.both)
    contactAddedPacket1.addRosterItem(item1)

    argument.getValue.handleIQRequest(contactAddedPacket1)

    assertThatReceiptOfPresenceChangeIsHandledCorrectly(tela.baseinterfaces.Presence.Unknown, Presence.Type.unavailable, Presence.Mode.available, stanzaListener)
    assertThatReceiptOfPresenceChangeIsHandledCorrectly(tela.baseinterfaces.Presence.Available, Presence.Type.available, Presence.Mode.available, stanzaListener)
    assertThatReceiptOfPresenceChangeIsHandledCorrectly(tela.baseinterfaces.Presence.Away, Presence.Type.available, Presence.Mode.away, stanzaListener)
    assertThatReceiptOfPresenceChangeIsHandledCorrectly(tela.baseinterfaces.Presence.Available, Presence.Type.available, Presence.Mode.chat, stanzaListener)
    assertThatReceiptOfPresenceChangeIsHandledCorrectly(tela.baseinterfaces.Presence.DoNotDisturb, Presence.Type.available, Presence.Mode.dnd, stanzaListener)
    assertThatReceiptOfPresenceChangeIsHandledCorrectly(tela.baseinterfaces.Presence.Away, Presence.Type.available, Presence.Mode.xa, stanzaListener)
    assertThatReceiptOfPresenceChangeIsHandledCorrectly(tela.baseinterfaces.Presence.Available, Presence.Type.available, null, stanzaListener)
  }

  @Test def addContact(): Unit = {
    val expectedRosterPacket: RosterPacket = new RosterPacket
    expectedRosterPacket.setType(IQ.Type.set)
    val expectedRosterItem: RosterPacket.Item = new RosterPacket.Item("newuser@bar.net", "newuser@bar.net")
    expectedRosterPacket.addRosterItem(expectedRosterItem)

    val session = createSessionWithMockConnectionForContactTesting()
    when(contactsConnection.createPacketCollectorAndSend(argThat(new IQPacketMatcher(expectedRosterPacket)))).thenReturn(new PacketCollectorImpl(contactsConnection))
    session.addContact("newuser@bar.net")

    verify(contactsConnection).createPacketCollectorAndSend(argThat(new IQPacketMatcher(expectedRosterPacket)))
  }

  private def createSessionWithMockConnectionForContactTesting(): SmackXMPPSession = {
    //TODO this is horrid, see comment at top of file
    contactsConnection = mock[XMPPConnection]
    when(contactsConnection.isAuthenticated).thenReturn(true)
    when(contactsConnection.isAnonymous).thenReturn(false)
    roster = Roster.getInstanceFor(contactsConnection)
    new SmackXMPPSession(contactsConnection, sessionListener, XMPPSettings("localhost", 5222, TestDomain, "disabled"))
  }

  private class PacketCollectorImpl(connection: XMPPConnection) extends PacketCollector(connection, PacketCollector.newConfiguration()) {
    override def nextResultOrThrow[P <: Stanza](): P = {
      null.asInstanceOf[P]
    }
  }

  @Test def contactAdded(): Unit = {
    //TODO I'm not very happy that we're testing against a presence type of Unknown here
    //but with our current testing approach it's tricky to set another value when the contact is being added

    createSessionWithUsername()
    val argument: ArgumentCaptor[AbstractIqRequestHandler] = ArgumentCaptor.forClass(classOf[AbstractIqRequestHandler])
    verify(connection).registerIQRequestHandler(argument.capture())

    sendContactAddedEventToHandler(argument.getValue, "foo@bar.net")
    verify(sessionListener).contactsAdded(List(ContactInfo("foo@bar.net", tela.baseinterfaces.Presence.Unknown)))

    sendContactAddedEventToHandler(argument.getValue, "bar@foo.net")
    verify(sessionListener).contactsAdded(List(ContactInfo("bar@foo.net", tela.baseinterfaces.Presence.Unknown)))
  }

  private def sendContactAddedEventToHandler(handler: AbstractIqRequestHandler, contact: String): Unit = {
    val contactAddedPacket = new RosterPacket()
    val item = new Item(contact, contact)
    item.setItemType(RosterPacket.ItemType.both)
    contactAddedPacket.addRosterItem(item)

    handler.handleIQRequest(contactAddedPacket)
  }

  @Test def callSignalPacket(): Unit = {
    val callSignal = new CallSignal(TestCallSignalData)
    val packetAsXML: NodeSeq = XML.loadString(callSignal.toXML.toString)
    assertEquals("iq", packetAsXML.asInstanceOf[Elem].label)

    // We explicitly set the type of the CallSignal to be set when we're actually sending it
    // Perhaps we should be making these packets type get by "default"
    assertEquals("get", packetAsXML.asInstanceOf[Elem].attributes.get("type").get.toString)
    assertEquals(RawTestCallSignalPacket, (packetAsXML \ "callSignal").toString())
  }

  @Test def sendCallSignal(): Unit = {
    val session = connectToServerAndGetSession
    session.sendCallSignal(TestBareJid, TestCallSignalData)
    val signal: CallSignal = createCallSignal(TestCallSignalData, TestBareJid + "/" + SmackXMPPSession.DefaultResourceName, Type.set)
    verify(connection).sendStanza(argThat(new IQPacketMatcher(signal)))
  }

  @Test def sendCallSignal_FullJID(): Unit = {
    val session = connectToServerAndGetSession
    session.sendCallSignal(TestBareJid + "/" + SmackXMPPSession.DefaultResourceName, TestCallSignalData)
    val signal: CallSignal = createCallSignal(TestCallSignalData, TestBareJid + "/" + SmackXMPPSession.DefaultResourceName, Type.set)
    verify(connection).sendStanza(argThat(new IQPacketMatcher(signal)))
  }

  @Test def callSignalPacketListener(): Unit = {
    connectToServer
    val packetListenerCaptor: ArgumentCaptor[StanzaListener] = ArgumentCaptor.forClass(classOf[StanzaListener])
    val packetFilterCaptor: ArgumentCaptor[StanzaFilter] = ArgumentCaptor.forClass(classOf[StanzaFilter])
    verify(connection, atLeastOnce()).addAsyncStanzaListener(packetListenerCaptor.capture(), packetFilterCaptor.capture())

    val signal: CallSignal = createCallSignal(TestCallSignalData, TestBareJid + "/" + SmackXMPPSession.DefaultResourceName, Type.set)

    // We use the index 0 because we expect the listener to be the first one added.
    assertTrue(getValueFromArgumentCaptor(packetFilterCaptor, 0).accept(signal))

    //verifying that our filter doesn't accept just a packet that isn't a call signal
    assertFalse(packetFilterCaptor.getValue.accept(new AdHocCommandData))

    signal.setFrom(TestBareJid)
    getValueFromArgumentCaptor(packetListenerCaptor, 0).processPacket(signal)

    verify(sessionListener).callSignalReceived(TestBareJid, TestCallSignalData)
  }

  @Test def callSignalProvider(): Unit = {
    val provider = ProviderManager.getIQProvider(SmackXMPPSession.CallSignalElementName, SmackXMPPSession.TelaURN)
    val xmlParser = PacketParserUtils.newXmppParser()
    xmlParser.setInput(new StringReader(RawTestCallSignalPacket))
    xmlParser.nextToken() //simulates the state of the parser when it gets passed to the provider in production
    assertTrue(new IQPacketMatcher(new CallSignal(TestCallSignalData)).matches(provider.parse(xmlParser)))
  }

  @Test def sendChatMessage(): Unit = {
    val session = connectToServerAndGetSession
    session.sendChatMessage(TestBareJid, TestChatMessageData)
    val message: Message = new Message(TestBareJid)
    message.setBody(TestChatMessageData)
    verify(connection).sendStanza(argThat(new ChatMessagePacketMatcher(message)))
  }

  @Test def receiveChatMessage(): Unit = {
    connectToServer
    val packetListenerCaptor: ArgumentCaptor[StanzaListener] = ArgumentCaptor.forClass(classOf[StanzaListener])
    verify(connection, atLeastOnce()).addSyncStanzaListener(packetListenerCaptor.capture(), anyObject[StanzaFilter]())

    val message = new Message("")
    message.setFrom(TestFullJid)
    message.setBody(TestChatMessageData)

    // We use the index 1 because the roster listener gets added first, then the chat listener (in accordance with our connectToServer method)
    getValueFromArgumentCaptor(packetListenerCaptor, 1).processPacket(message)
    verify(sessionListener).chatMessageReceived(TestBareJid, TestChatMessageData)
  }

  private def assertThatReceiptOfPresenceChangeIsHandledCorrectly(expectedResult: tela.baseinterfaces.Presence, xmppType: Presence.Type, xmppMode: Presence.Mode, stanzaListener: StanzaListener): Unit = {
    val presence: Presence = new Presence(xmppType, "", 0, xmppMode)
    presence.setFrom("foo@bar.net")
    reset(sessionListener) //Need to do this to ensure that old calls with same expected values don't cause failures
    stanzaListener.processPacket(presence)
    verify(sessionListener).presenceChanged(ContactInfo("foo@bar.net", expectedResult))
  }

  private def getValueFromArgumentCaptor[T](captor: ArgumentCaptor[T], index: Int): T = {
    captor.getAllValues.toList(index)
  }

  private def createSessionWithUsername(): XMPPSession = {
    val session = connectToServerAndGetSession
    when(connection.setUser(TestFullJid)).thenCallRealMethod()
    connection.setUser(TestFullJid)
    when(connection.getUser).thenCallRealMethod()
    session
  }

  private def createCallSignal(data: String, to: String, iqType: Type): CallSignal = {
    val result = new CallSignal(data)
    result.setTo(to)
    result.setType(iqType)
    result
  }

  private abstract class TestableXMPPConnection extends AbstractXMPPConnection(XMPPTCPConnectionConfiguration.builder().build()) {
    def setUser(username: String): Unit = {
      user = username
    }
  }

  private def connectToServer: Either[LoginFailure, XMPPSession] = {
    SmackXMPPSession.connectToServer(TestUsername, TestPassword, connection, XMPPSettings("localhost", 5222, TestDomain, "disabled"), sessionListener)
  }

  private def connectToServerAndGetSession: XMPPSession = {
    connectToServer.right.get
  }

  private def verifyPresencePacketSent(presence: Presence): Unit = {
    verify(connection).sendStanza(argThat(new PresenceMatcher(presence)))
  }

  private class ChatMessagePacketMatcher(private val expected: Message) extends ArgumentMatcher[Message] {
    override def matches(argument: scala.Any): Boolean = {
      argument match {
        case actual: Message =>
          expected.getTo == actual.getTo && expected.getBody == actual.getBody
        case _ => false
      }
    }
  }

  private class IQPacketMatcher(private val expected: IQ) extends ArgumentMatcher[IQ] {
    override def matches(argument: scala.Any): Boolean = {
      argument match {
        case actual: IQ =>
          expected.getTo == actual.getTo && expected.getType == actual.getType &&
            XML.loadString(expected.getChildElementXML.toString) == XML.loadString(actual.getChildElementXML.toString)
        case _ => false
      }
    }
  }

  private class RegistrationMatcher(private val expected: Registration) extends ArgumentMatcher[Registration] {
    override def matches(argument: Any): Boolean = {
      argument match {
        case actual: Registration =>
          expected.getType == actual.getType && mapAsScalaMap(expected.getAttributes) == mapAsScalaMap(actual.getAttributes)
        case _ => false
      }
    }
  }

  private class PresenceMatcher(private val expected: Presence) extends ArgumentMatcher[Presence] {
    override def matches(argument: Any): Boolean = {
      argument match {
        case actual: Presence => expected.getType == actual.getType &&
          expected.getMode == actual.getMode &&
          expected.getStatus == actual.getStatus &&
          expected.getPriority == actual.getPriority
        case _ => false
      }
    }
  }
}
