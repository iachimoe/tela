package tela.xmpp

import com.typesafe.scalalogging.Logger
import org.jivesoftware.smack.SmackException.ConnectionException
import org.jivesoftware.smack.XMPPException.XMPPErrorException
import org.jivesoftware.smack._
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.packet.Presence.Mode
import org.jivesoftware.smack.sasl.SASLErrorException
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smackx.iqregister.AccountManager
import org.jivesoftware.smackx.pubsub._
import org.jxmpp.util.XmppStringUtils
import org.slf4j.LoggerFactory
import tela.baseinterfaces
import tela.baseinterfaces._
import tela.xmpp.SmackXMPPSession._

import scala.collection.JavaConversions
import scala.collection.JavaConversions._

object SmackXMPPSession {
  private val log = Logger(LoggerFactory.getLogger(getClass))

  private[xmpp] val DefaultResourceName: String = "Work"

  private[xmpp] val ChangePasswordResource: String = "ChangePassword"

  private[xmpp] val DefaultPriority = 0
  private[xmpp] val DefaultStatusText = ""

  def connectToServer(username: String, password: String, xmppSettings: XMPPSettings, sessionListener: XMPPSessionListener): Either[LoginFailure, XMPPSession] = {
    connectToServer(username, password, createTCPConnectionToServer(xmppSettings), xmppSettings, sessionListener)
  }

  private[xmpp] def connectToServer(username: String, password: String, connection: AbstractXMPPConnection, xmppSettings: XMPPSettings, sessionListener: XMPPSessionListener): Either[LoginFailure, XMPPSession] = {
    connectToServer(username, password, DefaultResourceName, connection, xmppSettings, sessionListener)
  }

  private[xmpp] def connectToServer(username: String, password: String, resource: String, connection: AbstractXMPPConnection, xmppSettings: XMPPSettings, sessionListener: XMPPSessionListener): Either[LoginFailure, XMPPSession] = {
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
      connection.login(username, password, resource)
      val session: SmackXMPPSession = new SmackXMPPSession(connection, sessionListener, xmppSettings)
      //TODO There seems to be a race condition with this not getting set on time.
      connection.getRoster.addRosterListener(new ContactListChangeListener(session))
      Right(session)
    } catch {
      case ex: SASLErrorException =>
        log.info("Login failed for user {} due to bad credentials", username)
        connection.disconnect()
        Left(LoginFailure.InvalidCredentials)
    }
  }

  private def createTCPConnectionToServer(xmppSettings: XMPPSettings): XMPPTCPConnection = {
    log.debug("Creating new connection object")
    val config = new ConnectionConfiguration(xmppSettings.hostname, xmppSettings.port, xmppSettings.domain)
    config.setSecurityMode(xmppSettings.securityMode match {
      case "required" => ConnectionConfiguration.SecurityMode.required
      case "enabled" => ConnectionConfiguration.SecurityMode.enabled
      case "disabled" => ConnectionConfiguration.SecurityMode.disabled
      case invalidMode: String => throw new IllegalArgumentException("Invalid security mode " + invalidMode)
    })
    new XMPPTCPConnection(config)
  }

  private class ContactListChangeListener(private val xmppSession: SmackXMPPSession) extends RosterListener {
    override def entriesAdded(addresses: java.util.Collection[String]): Unit = {
      log.debug("{} new entries added to contact list for user {}", addresses.size.toString, xmppSession.connection.getUser)
      xmppSession.sessionListener.contactsAdded(addresses.map((address) => ContactInfo(address, xmppSession.getPresenceForContact(address))).toList)
    }

    override def entriesUpdated(addresses: java.util.Collection[String]): Unit = {
    }

    override def entriesDeleted(addresses: java.util.Collection[String]): Unit = {
    }

    override def presenceChanged(presence: Presence): Unit = {
      log.debug("User {} received presence changed info for {}", xmppSession.connection.getUser, presence.getFrom)
      xmppSession.sessionListener.presenceChanged(ContactInfo(XmppStringUtils.parseBareAddress(presence.getFrom), xmppSession.getTelaPresenceFromXMPPPresence(presence)))
    }
  }

}

private class SmackXMPPSession(private val connection: AbstractXMPPConnection, private val sessionListener: XMPPSessionListener, private val xmppSettings: XMPPSettings) extends XMPPSession {
  override def disconnect(): Unit = {
    log.info("Disconnecting {}", connection.getUser)
    connection.disconnect()
  }

  override def setPresence(presence: tela.baseinterfaces.Presence): Unit = {
    log.info("User {} setting presence to {}", connection.getUser, presence)
    connection.sendPacket(new Presence(Presence.Type.available, DefaultStatusText, DefaultPriority, getXMPPPresenceFromTelaPresence(presence)))
  }

  override def changePassword(existingPassword: String, newPassword: String): Boolean = {
    changePassword(existingPassword, newPassword, createTCPConnectionToServer(xmppSettings))
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

  // BEGIN UNTESTED PUBSUB STUFF

  override def publish(nodeName: String, content: String): Unit = {
    val manager = new PubSubManager(connection, XmppStringUtils.parseBareAddress(connection.getUser))

    val node = getNode(manager, nodeName, connection.getUser).getOrElse(createNode(manager, nodeName))

    log.info("User {} publishing to node {}", connection.getUser, node)
    val payload = new SimplePayload(null, null, content)
    val payloadItem = new PayloadItem(null, payload)
    node.publish(payloadItem)
  }

  override def getPublishedData(user: String, nodeName: String): String = {
    val manager = new PubSubManager(connection, user)

    getNode(manager, nodeName, user) match {
      case Some(node) =>
        val items: List[PayloadItem[SimplePayload]] = JavaConversions.asScalaBuffer(node.getItems[PayloadItem[SimplePayload]]()).toList
        if (items.nonEmpty)
          items(0).getPayload.toXML.toString
        else
          "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'></rdf:RDF>"
      case None => "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'></rdf:RDF>"
    }
  }

  private def getNode(manager: PubSubManager, node: String, user: String): Option[LeafNode] = {
    try {
      log.info("User {} attempting to retrieve node {} belonging to {}", connection.getUser, node, user)
      Some(manager.getNode[LeafNode](node))
    } catch {
      case ex: XMPPErrorException =>
        log.info("Node not found (xmpp exception)")
        None
      case ex: AssertionError =>
        log.info("Node not found (assertion error)")
        None
    }
  }

  private def createNode(manager: PubSubManager, nodeName: String): LeafNode = {
    log.info("User {} creating node {}", connection.getUser, nodeName)
    val config = new ConfigureForm(FormType.submit)
    config.setPersistentItems(true)
    config.setDeliverPayloads(true)
    config.setAccessModel(AccessModel.presence)
    config.setPublishModel(PublishModel.publishers)
    config.setMaxItems(1)
    config.setSubscribe(true)

    manager.createNode(nodeName, config).asInstanceOf[LeafNode]
  }

  // END UNTESTED PUBSUB STUFF

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

  private[xmpp] def changePassword(existingPassword: String, newPassword: String, changePasswordConnection: AbstractXMPPConnection): Boolean = {
    if (newPassword.isEmpty) {
      log.info("Rejecting blank password change request from user {}", connection.getUser)
      changePasswordConnection.disconnect()
      false
    }
    else if (connectToServer(XmppStringUtils.parseLocalpart(connection.getUser), existingPassword, ChangePasswordResource, changePasswordConnection, null, new StubSessionListener).isLeft) {
      //TODO passing in null above is a bit sketchy....
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
