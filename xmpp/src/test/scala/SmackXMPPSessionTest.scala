package tela.xmpp

import org.jivesoftware.smack.SmackException.ConnectionException
import org.jivesoftware.smack._
import org.jivesoftware.smack.packet.{IQ, Presence, Registration, RosterPacket}
import org.jivesoftware.smack.sasl.SASLMechanism.SASLFailure
import org.jivesoftware.smack.sasl.{SASLError, SASLErrorException}
import org.junit.Assert._
import org.junit.{Before, Test}
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.mockito.{ArgumentCaptor, ArgumentMatcher}
import org.scalatest.junit.AssertionsForJUnit
import org.scalatest.mock.MockitoSugar
import tela.baseinterfaces.{XMPPSession, ContactInfo, LoginFailure, XMPPSessionListener}

import scala.collection.JavaConversions._

class SmackXMPPSessionTest extends AssertionsForJUnit with MockitoSugar {
  private val SmackUsernameKey = "username"
  private val SmackPasswordKey = "password"

  private val TestUsername = "user"
  private val TestPassword = "pass"
  private val TestDomain = "example.com"
  private val TestResource = "res"
  private val TestBareJid = TestUsername + "@" + TestDomain
  private val TestFullJid = TestBareJid + "/" + TestResource

  private var connection: XMPPConnection = null
  private var roster: Roster = null
  private var sessionListener: XMPPSessionListener = null

  @Before def initialize(): Unit = {
    connection = mock[XMPPConnection]
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
    val session = connectToServerAndGetSession
    when(connection.getUser).thenReturn(TestFullJid)

    val changePasswordConnection = mock[XMPPConnection]
    when(changePasswordConnection.getRoster).thenReturn(roster)
    when(changePasswordConnection.getUser).thenReturn(TestFullJid)

    val expectedRegistrationPacket: Registration = new Registration
    expectedRegistrationPacket.setType(IQ.Type.SET)
    expectedRegistrationPacket.setAttributes(scala.collection.JavaConversions.mapAsJavaMap(Map(SmackUsernameKey -> TestUsername, SmackPasswordKey -> "newPassword")))

    val packetCollector = mock[PacketCollector]
    when(changePasswordConnection.createPacketCollectorAndSend(argThat(new RegistrationMatcher(expectedRegistrationPacket)))).thenReturn(packetCollector)

    assertTrue(session.asInstanceOf[SmackXMPPSession].changePassword(TestPassword, "newPassword", changePasswordConnection))

    verify(changePasswordConnection).connect()
    verify(changePasswordConnection).login(TestUsername, TestPassword, SmackXMPPSession.ChangePasswordResource)
    verify(changePasswordConnection).createPacketCollectorAndSend(argThat(new RegistrationMatcher(expectedRegistrationPacket)))
    verify(changePasswordConnection).disconnect()
  }

  @Test def changePassword_incorrectOldPassword(): Unit = {
    val session = connectToServerAndGetSession
    when(connection.getUser).thenReturn(TestFullJid)

    val changePasswordConnection = mock[XMPPConnection]
    when(changePasswordConnection.getRoster).thenReturn(roster)
    when(changePasswordConnection.login(TestUsername, "wrongPassword", SmackXMPPSession.ChangePasswordResource)).thenThrow(new SASLErrorException("", new SASLFailure(SASLError.not_authorized.toString)))

    assertFalse(session.asInstanceOf[SmackXMPPSession].changePassword("wrongPassword", "newPassword", changePasswordConnection))
    verify(changePasswordConnection).disconnect()
  }

  @Test def changePassword_invalidNewPassword(): Unit = {
    val session = connectToServerAndGetSession
    val changePasswordConnection = mock[XMPPConnection]
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

    val presence: Presence = new Presence(Presence.Type.unavailable, "", 0, Presence.Mode.available)
    presence.setFrom(TestFullJid)

    argument.getValue.presenceChanged(presence)
    verify(sessionListener).presenceChanged(ContactInfo(TestBareJid, tela.baseinterfaces.Presence.Unknown))

    presence.setType(Presence.Type.available)
    argument.getValue.presenceChanged(presence)
    verify(sessionListener).presenceChanged(ContactInfo(TestBareJid, tela.baseinterfaces.Presence.Available))

    presence.setMode(Presence.Mode.away)
    argument.getValue.presenceChanged(presence)
    verify(sessionListener).presenceChanged(ContactInfo(TestBareJid, tela.baseinterfaces.Presence.Away))

    reset(sessionListener)
    presence.setMode(Presence.Mode.chat)
    argument.getValue.presenceChanged(presence)
    verify(sessionListener).presenceChanged(ContactInfo(TestBareJid, tela.baseinterfaces.Presence.Available))

    presence.setMode(Presence.Mode.dnd)
    argument.getValue.presenceChanged(presence)
    verify(sessionListener).presenceChanged(ContactInfo(TestBareJid, tela.baseinterfaces.Presence.DoNotDisturb))

    reset(sessionListener)
    presence.setMode(Presence.Mode.xa)
    argument.getValue.presenceChanged(presence)
    verify(sessionListener).presenceChanged(ContactInfo(TestBareJid, tela.baseinterfaces.Presence.Away))

    presence.setMode(null)
    argument.getValue.presenceChanged(presence)
    verify(sessionListener).presenceChanged(ContactInfo(TestBareJid, tela.baseinterfaces.Presence.Available))
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

  private def connectToServer: Either[LoginFailure, XMPPSession] = {
    SmackXMPPSession.connectToServer(TestUsername, TestPassword, connection, sessionListener)
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
