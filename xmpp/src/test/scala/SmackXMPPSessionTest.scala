package tela.xmpp

import java.io.StringReader

import org.jivesoftware.smack.SmackException.ConnectionException
import org.jivesoftware.smack._
import org.jivesoftware.smack.filter.{PacketFilter, PacketIDFilter}
import org.jivesoftware.smack.packet.IQ.Type
import org.jivesoftware.smack.packet.{IQ, Message, Presence, RosterPacket}
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.sasl.packet.SaslStreamElements.SASLFailure
import org.jivesoftware.smack.sasl.{SASLError, SASLErrorException}
import org.jivesoftware.smack.util.PacketParserUtils
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
import scala.xml.{Utility, XML}

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
  private val RawTestCallSignalPacket = <callSignal xmlns={SmackXMPPSession.TelaURN}>
    {TestCallSignalData}
  </callSignal>.toString()
  private val TestChatMessageData = "Chat message"

  private var connection: TestableXMPPConnection = null
  private var roster: Roster = null
  private var sessionListener: XMPPSessionListener = null

  @Before def initialize(): Unit = {
    connection = mock[TestableXMPPConnection]
    sessionListener = mock[XMPPSessionListener]
    roster = mock[Roster]
    when(connection.getRoster).thenReturn(roster)
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
    val session: XMPPSession = createSessionAndConfigureForPasswordChange()

    val changePasswordConnection = mock[TestableXMPPConnection]
    when(changePasswordConnection.getRoster).thenReturn(roster)
    when(changePasswordConnection.setUser(TestFullJid)).thenCallRealMethod()
    changePasswordConnection.setUser(TestFullJid)
    when(changePasswordConnection.getUser).thenCallRealMethod()

    val expectedRegistrationPacket: Registration = new Registration(scala.collection.JavaConversions.mapAsJavaMap(Map(SmackUsernameKey -> TestUsername, SmackPasswordKey -> "newPassword")))
    expectedRegistrationPacket.setType(IQ.Type.set)

    val packetCollector = mock[PacketCollector]
    when(changePasswordConnection.createPacketCollectorAndSend(isA(classOf[PacketIDFilter]), argThat(new RegistrationMatcher(expectedRegistrationPacket)))).thenReturn(packetCollector)

    assertTrue(session.asInstanceOf[SmackXMPPSession].changePassword(TestPassword, "newPassword", changePasswordConnection))

    verify(changePasswordConnection).connect()
    verify(changePasswordConnection).login(TestUsername, TestPassword, SmackXMPPSession.ChangePasswordResource)
    verify(changePasswordConnection).createPacketCollectorAndSend(isA(classOf[PacketIDFilter]), argThat(new RegistrationMatcher(expectedRegistrationPacket)))
    verify(changePasswordConnection).disconnect()
  }

  @Test def changePassword_incorrectOldPassword(): Unit = {
    val session: XMPPSession = createSessionAndConfigureForPasswordChange()

    val changePasswordConnection = mock[TestableXMPPConnection]
    when(changePasswordConnection.getRoster).thenReturn(roster)
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
    when(roster.getEntries).thenReturn(createRosterEntries(List("friend1@domain", "friend2@domain")))
    when(roster.getPresence(anyString())).thenReturn(new Presence(Presence.Type.available, "", 0, Presence.Mode.away))

    val session = connectToServer.right.get
    session.getContactList()

    verify(sessionListener).contactsAdded(List(ContactInfo("friend1@domain", tela.baseinterfaces.Presence.Away), ContactInfo("friend2@domain", tela.baseinterfaces.Presence.Away)))
  }

  @Test def presenceChanged(): Unit = {
    connectToServer
    val argument: ArgumentCaptor[RosterListener] = ArgumentCaptor.forClass(classOf[RosterListener])
    verify(roster).addRosterListener(argument.capture())

    assertThatReceiptOfPresenceChangeIsHandledCorrectly(tela.baseinterfaces.Presence.Unknown, Presence.Type.unavailable, Presence.Mode.available, argument)
    assertThatReceiptOfPresenceChangeIsHandledCorrectly(tela.baseinterfaces.Presence.Available, Presence.Type.available, Presence.Mode.available, argument)
    assertThatReceiptOfPresenceChangeIsHandledCorrectly(tela.baseinterfaces.Presence.Away, Presence.Type.available, Presence.Mode.away, argument)
    assertThatReceiptOfPresenceChangeIsHandledCorrectly(tela.baseinterfaces.Presence.Available, Presence.Type.available, Presence.Mode.chat, argument)
    assertThatReceiptOfPresenceChangeIsHandledCorrectly(tela.baseinterfaces.Presence.DoNotDisturb, Presence.Type.available, Presence.Mode.dnd, argument)
    assertThatReceiptOfPresenceChangeIsHandledCorrectly(tela.baseinterfaces.Presence.Away, Presence.Type.available, Presence.Mode.xa, argument)
    assertThatReceiptOfPresenceChangeIsHandledCorrectly(tela.baseinterfaces.Presence.Available, Presence.Type.available, null, argument)
  }

  @Test def addContact(): Unit = {
    connectToServerAndGetSession.addContact("newuser@bar.net")
    verify(roster).createEntry("newuser@bar.net", "newuser@bar.net", Array[String]())
  }

  @Test def contactAdded(): Unit = {
    connectToServer
    val argument: ArgumentCaptor[RosterListener] = ArgumentCaptor.forClass(classOf[RosterListener])
    verify(roster).addRosterListener(argument.capture())
    when(roster.getPresence(anyString())).thenReturn(new Presence(Presence.Type.available, "", 0, Presence.Mode.dnd))

    argument.getValue.entriesAdded(List("foo@bar.net", "bar@foo.net"))
    verify(sessionListener).contactsAdded(List(ContactInfo("foo@bar.net", tela.baseinterfaces.Presence.DoNotDisturb),
      ContactInfo("bar@foo.net", tela.baseinterfaces.Presence.DoNotDisturb)))
  }

  @Test def callSignalPacket(): Unit = {
    val callSignal = new CallSignal(TestCallSignalData)

    assertTrue(new IQPacketMatcher(new IQ() {
      override def getChildElementXML: CharSequence = RawTestCallSignalPacket
    }).matches(callSignal))
  }

  @Test def sendCallSignal(): Unit = {
    val session = connectToServerAndGetSession
    session.sendCallSignal(TestBareJid, TestCallSignalData)
    val signal: CallSignal = createCallSignal(TestCallSignalData, TestBareJid + "/" + SmackXMPPSession.DefaultResourceName, Type.set)
    verify(connection).sendPacket(argThat(new IQPacketMatcher(signal)))
  }

  @Test def sendCallSignal_FullJID(): Unit = {
    val session = connectToServerAndGetSession
    session.sendCallSignal(TestBareJid + "/" + SmackXMPPSession.DefaultResourceName, TestCallSignalData)
    val signal: CallSignal = createCallSignal(TestCallSignalData, TestBareJid + "/" + SmackXMPPSession.DefaultResourceName, Type.set)
    verify(connection).sendPacket(argThat(new IQPacketMatcher(signal)))
  }

  @Test def callSignalPacketListener(): Unit = {
    connectToServer
    val packetListenerCaptor: ArgumentCaptor[PacketListener] = ArgumentCaptor.forClass(classOf[PacketListener])
    val packetFilterCaptor: ArgumentCaptor[PacketFilter] = ArgumentCaptor.forClass(classOf[PacketFilter])
    verify(connection, atLeastOnce()).addPacketListener(packetListenerCaptor.capture(), packetFilterCaptor.capture())

    val signal: CallSignal = createCallSignal(TestCallSignalData, TestBareJid + "/" + SmackXMPPSession.DefaultResourceName, Type.set)

    // We use the index 1 because we expect the listener to be the second one added.
    assertTrue(getValueFromArgumentCaptor(packetFilterCaptor, 1).accept(signal))

    assertFalse(packetFilterCaptor.getValue.accept(new IQ() {
      override def getChildElementXML: CharSequence = {
        "not a call signal packet"
      }
    }))

    signal.setFrom(TestBareJid)
    getValueFromArgumentCaptor(packetListenerCaptor, 1).processPacket(signal)

    verify(sessionListener).callSignalReceived(TestBareJid, TestCallSignalData)
  }

  @Test def callSignalProvider(): Unit = {
    val provider = ProviderManager.getIQProvider("callSignal", SmackXMPPSession.TelaURN)
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
    verify(connection).sendPacket(argThat(new ChatMessagePacketMatcher(message)))
  }

  @Test def receiveChatMessage(): Unit = {
    connectToServer
    val packetListenerCaptor: ArgumentCaptor[PacketListener] = ArgumentCaptor.forClass(classOf[PacketListener])
    verify(connection, atLeastOnce()).addPacketListener(packetListenerCaptor.capture(), anyObject[PacketFilter]())

    val message = new Message("")
    message.setFrom(TestFullJid)
    message.setBody(TestChatMessageData)

    // We use the index 0 because we expect the listener to be the first one added.
    getValueFromArgumentCaptor(packetListenerCaptor, 0).processPacket(message)
    verify(sessionListener).chatMessageReceived(TestBareJid, TestChatMessageData)
  }

  private def assertThatReceiptOfPresenceChangeIsHandledCorrectly(expectedResult: tela.baseinterfaces.Presence, xmppType: Presence.Type, xmppMode: Presence.Mode, rosterListenerCaptor: ArgumentCaptor[RosterListener]): Unit = {
    val presence: Presence = new Presence(xmppType, "", 0, xmppMode)
    presence.setFrom(TestFullJid)
    reset(sessionListener) //Need to do this to ensure that old calls with same expected values don't cause failures
    rosterListenerCaptor.getValue.presenceChanged(presence)
    verify(sessionListener).presenceChanged(ContactInfo(TestBareJid, expectedResult))
  }

  private def getValueFromArgumentCaptor[T](captor: ArgumentCaptor[T], index: Int): T = {
    captor.getAllValues.toList(index)
  }

  private def createSessionAndConfigureForPasswordChange(): XMPPSession = {
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

  private abstract class TestableXMPPConnection extends AbstractXMPPConnection(new ConnectionConfiguration("")) {
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
    verify(connection).sendPacket(argThat(new PresenceMatcher(presence)))
  }

  private def createRosterEntries(jids: List[String]): java.util.Collection[RosterEntry] = {
    asJavaCollection(jids.map(jid => {
      //For some reason wasn't able to mock RosterEntry *grumble*
      val constructor = classOf[RosterEntry].getDeclaredConstructors()(0)
      constructor.setAccessible(true)
      constructor.newInstance(jid, "nickname", RosterPacket.ItemType.both, RosterPacket.ItemStatus.subscribe, null, null).asInstanceOf[RosterEntry]
    }))
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
            Utility.trim(XML.loadString(expected.getChildElementXML.toString)) == Utility.trim(XML.loadString(actual.getChildElementXML.toString))
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
