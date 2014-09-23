package tela.xmpp

import java.util

import com.typesafe.scalalogging.Logger
import org.jivesoftware.smack.SmackException.ConnectionException
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.packet.Presence.Mode
import org.jivesoftware.smack.sasl.SASLErrorException
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.util.StringUtils
import org.jivesoftware.smack._
import org.slf4j.LoggerFactory
import tela.baseinterfaces
import tela.baseinterfaces.{ContactInfo, LoginFailure, XMPPSession, XMPPSessionListener}
import SmackXMPPSession._
import scala.collection.JavaConversions._

object SmackXMPPSession {
  private val log = Logger(LoggerFactory.getLogger(getClass))

  private val ServerHostname: String = "localhost"

  private val ServerPort: Int = 5222

  private[xmpp] val DefaultResourceName: String = "Work"

  private[xmpp] val ChangePasswordResource: String = "ChangePassword"

  private[xmpp] val DefaultPriority = 0
  private[xmpp] val DefaultStatusText = ""

  def connectToServer(username: String, password: String, sessionListener: XMPPSessionListener): Either[LoginFailure, XMPPSession] = {
    connectToServer(username, password, createTCPConnectionToServer(), sessionListener)
  }

  private[xmpp] def connectToServer(username: String, password: String, connection: XMPPConnection, sessionListener: XMPPSessionListener): Either[LoginFailure, XMPPSession] = {
    connectToServer(username, password, DefaultResourceName, connection, sessionListener)
  }

  private[xmpp] def connectToServer(username: String, password: String, resource: String, connection: XMPPConnection, sessionListener: XMPPSessionListener): Either[LoginFailure, XMPPSession] = {
    try {
      log.debug("Connecting to server for user {}", username)
      connection.connect()
    }
    catch {
      case ex: ConnectionException =>
        log.error(s"Failed to connect to server for user $username", ex)
        connection.disconnect()
        return Left(LoginFailure.ConnectionFailure)
    }

    try {
      log.info("User {} attempting login", username)
      //according to the smack docs, this should be done before logging in
      val listener: ContactListChangeListener = new ContactListChangeListener
      connection.getRoster.addRosterListener(listener)
      connection.login(username, password, resource)
      val session: SmackXMPPSession = new SmackXMPPSession(connection, sessionListener)
      //TODO There seems to be a race condition with this not getting set on time.
      listener.setSession(session)
      Right(session)
    } catch {
      case ex: SASLErrorException =>
        log.info("Login failed for user {} due to bad credentials", username)
        connection.disconnect()
        Left(LoginFailure.InvalidCredentials)
    }
  }

  private def createTCPConnectionToServer(): XMPPTCPConnection = {
    log.debug("Creating new connection object")
    val config = new ConnectionConfiguration(ServerHostname, ServerPort, "")
    config.setSecurityMode(ConnectionConfiguration.SecurityMode.disabled)
    new XMPPTCPConnection(config)
  }

  private class ContactListChangeListener extends RosterListener {
    private var xmppSession: SmackXMPPSession = null

    override def entriesAdded(addresses: util.Collection[String]): Unit = {
      log.debug("{} new entries added to contact list for user {}", addresses.size.toString, xmppSession.connection.getUser)
      xmppSession.sessionListener.contactsAdded(addresses.map((address) => ContactInfo(address, xmppSession.getPresenceForContact(address))).toList)
    }

    override def entriesUpdated(addresses: util.Collection[String]): Unit = {
    }

    override def entriesDeleted(addresses: util.Collection[String]): Unit = {
    }

    override def presenceChanged(presence: Presence): Unit = {
      log.debug("User {} received presence changed info for {}", xmppSession.connection.getUser, presence.getFrom)
      xmppSession.sessionListener.presenceChanged(ContactInfo(StringUtils.parseBareAddress(presence.getFrom), xmppSession.getTelaPresenceFromXMPPPresence(presence)))
    }

    private[SmackXMPPSession] def setSession(session: SmackXMPPSession): Unit = {
      xmppSession = session
    }
  }
}

private class SmackXMPPSession(private val connection: XMPPConnection, private val sessionListener: XMPPSessionListener) extends XMPPSession {
  override def disconnect(): Unit = {
    log.info("Disconnecting {}", connection.getUser)
    connection.disconnect()
  }

  override def setPresence(presence: tela.baseinterfaces.Presence): Unit = {
    log.info("User {} setting presence to {}", connection.getUser, presence)
    connection.sendPacket(new Presence(Presence.Type.available, DefaultStatusText, DefaultPriority, getXMPPPresenceFromTelaPresence(presence)))
  }

  override def changePassword(existingPassword: String, newPassword: String): Boolean = {
    changePassword(existingPassword, newPassword, createTCPConnectionToServer())
  }

  override def getContactList(): Unit = {
    log.info("Retrieving contact list for user {}", connection.getUser)
    sessionListener.contactsAdded(collectionAsScalaIterable(connection.getRoster.getEntries).map(
        (entry: RosterEntry) => ContactInfo(entry.getUser, getTelaPresenceFromXMPPPresence(connection.getRoster.getPresence(entry.getUser)))).toArray.toList)
  }

  override def addContact(address: String): Unit = {
    log.info("User {} adding contact {}", connection.getUser, address)
    connection.getRoster.createEntry(address, address, Array[String]())
  }

  private def getXMPPPresenceFromTelaPresence(presence: baseinterfaces.Presence): Mode = {
    presence match {
      case tela.baseinterfaces.Presence.Available => Presence.Mode.available
      case tela.baseinterfaces.Presence.Away => Presence.Mode.away
      case tela.baseinterfaces.Presence.DoNotDisturb => Presence.Mode.dnd
    }
  }

  private def getTelaPresenceFromXMPPPresence(presence: Presence): tela.baseinterfaces.Presence = {
    if (presence.getType == Presence.Type.available) {
      presence.getMode match {
        case Presence.Mode.available => baseinterfaces.Presence.Available
        case Presence.Mode.away => baseinterfaces.Presence.Away
        case Presence.Mode.chat => baseinterfaces.Presence.Available
        case Presence.Mode.dnd => baseinterfaces.Presence.DoNotDisturb
        case Presence.Mode.xa => baseinterfaces.Presence.Away
        case null => baseinterfaces.Presence.Available
      }
    }
    else
      tela.baseinterfaces.Presence.Unknown
  }

  private def getPresenceForContact(contact: String): tela.baseinterfaces.Presence = {
    getTelaPresenceFromXMPPPresence(connection.getRoster.getPresence(contact))
  }

  private[xmpp] def changePassword(existingPassword: String, newPassword: String, changePasswordConnection: XMPPConnection): Boolean = {
    if (newPassword.isEmpty)
    {
      log.info("Rejecting blank password change request from user {}", connection.getUser)
      changePasswordConnection.disconnect()
      false
    }
    else if (connectToServer(StringUtils.parseName(connection.getUser), existingPassword, ChangePasswordResource, changePasswordConnection, new StubSessionListener).isLeft) {
      log.info("Failed to change password for user {} due to connection error", connection.getUser)
      false
    }
    else {
      log.info("Changing password for user {}", connection.getUser)
      val accountManager = AccountManager.getInstance(changePasswordConnection)
      accountManager.changePassword(newPassword)
      changePasswordConnection.disconnect()
      true
    }
  }

  private class StubSessionListener extends XMPPSessionListener {
    override def contactsAdded(contacts: List[ContactInfo]): Unit = {}

    override def contactsRemoved(contacts: List[String]): Unit = {}

    override def presenceChanged(contact: ContactInfo): Unit = {}
  }
}
