package tela.xmpp

import java.net.URI

import com.typesafe.scalalogging.Logger
import org.jivesoftware.smack.SmackException.ConnectionException
import org.jivesoftware.smack.XMPPException.XMPPErrorException
import org.jivesoftware.smack._
import org.jivesoftware.smack.chat2.{Chat, ChatManager, IncomingChatMessageListener}
import org.jivesoftware.smack.filter.StanzaTypeFilter
import org.jivesoftware.smack.packet.IQ.{IQChildElementXmlStringBuilder, Type}
import org.jivesoftware.smack.packet.Presence.Mode
import org.jivesoftware.smack.packet._
import org.jivesoftware.smack.provider.{IQProvider, ProviderManager}
import org.jivesoftware.smack.roster.Roster.SubscriptionMode
import org.jivesoftware.smack.roster.{Roster, RosterListener}
import org.jivesoftware.smack.sasl.SASLErrorException
import org.jivesoftware.smack.tcp.{XMPPTCPConnection, XMPPTCPConnectionConfiguration}
import org.jivesoftware.smackx.iqregister.AccountManager
import org.jivesoftware.smackx.pubsub.PubSubException.NotAPubSubNodeException
import org.jivesoftware.smackx.pubsub._
import org.jivesoftware.smackx.xdata.packet.DataForm
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import org.jxmpp.jid.{EntityBareJid, Jid}
import org.jxmpp.util.XmppStringUtils
import org.slf4j.LoggerFactory
import org.xmlpull.v1.XmlPullParser
import tela.baseinterfaces
import tela.baseinterfaces._
import tela.xmpp.SmackXMPPSession._

import scala.jdk.CollectionConverters._

object SmackXMPPSession {
  //TODO Allowing this for now in order to facilitate non-SSL localhost connections...
  AccountManager.sensitiveOperationOverInsecureConnectionDefault(true)
  private val log = Logger(LoggerFactory.getLogger(getClass))

  private[xmpp] val DefaultResourceName: String = "Work"

  private[xmpp] val ChangePasswordResource: String = "ChangePassword"

  private[xmpp] val DefaultPriority = 0
  private[xmpp] val DefaultStatusText = ""

  private[xmpp] val TelaURN = "urn:tela"
  private[xmpp] val CallSignalElementName = "callSignal"

  ProviderManager.addIQProvider(CallSignalElementName, TelaURN, new CallSignalProvider)

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
      connection.login(username, password, Resourcepart.from(resource))
      val session: SmackXMPPSession = new SmackXMPPSession(connection, sessionListener, xmppSettings)

      Roster.getInstanceFor(connection).setSubscriptionMode(SubscriptionMode.accept_all)
      Roster.getInstanceFor(connection).addRosterListener(new ContactListChangeListener(session))
      ChatManager.getInstanceFor(connection).addIncomingListener(new TelaChatManagerListener(session))
      connection.addAsyncStanzaListener(new CallSignalPacketListener(session), new StanzaTypeFilter(classOf[CallSignal]))
      Right(session)
    } catch {
      case _: SASLErrorException =>
        log.info("Login failed for user {} due to bad credentials", username)
        connection.disconnect()
        Left(LoginFailure.InvalidCredentials)
    }
  }

  private def createTCPConnectionToServer(xmppSettings: XMPPSettings): XMPPTCPConnection = {
    log.debug("Creating new connection object")
    SmackConfiguration.DEBUG = xmppSettings.debug
    val config = XMPPTCPConnectionConfiguration.builder().
      setHost(xmppSettings.hostname).
      setPort(xmppSettings.port).
      setXmppDomain(JidCreate.domainBareFrom(xmppSettings.domain)).
      setSecurityMode(xmppSettings.securityMode match {
        case "required" => ConnectionConfiguration.SecurityMode.required
        case "enabled" => ConnectionConfiguration.SecurityMode.ifpossible
        case "disabled" => ConnectionConfiguration.SecurityMode.disabled
        case invalidMode => throw new IllegalArgumentException(s"Invalid security mode $invalidMode")
      }).build()
    new XMPPTCPConnection(config)
  }

  private class ContactListChangeListener(private val xmppSession: SmackXMPPSession) extends RosterListener {
    override def entriesAdded(addresses: java.util.Collection[Jid]): Unit = {
      log.debug("{} new entries added to contact list for user {}", addresses.size.toString, xmppSession.connection.getUser)
      xmppSession.sessionListener.contactsAdded(addresses.asScala.map(address => ContactInfo(address.asUnescapedString(), xmppSession.getPresenceForContact(address.asUnescapedString()))).toVector)
    }

    override def entriesUpdated(addresses: java.util.Collection[Jid]): Unit = {
    }

    override def entriesDeleted(addresses: java.util.Collection[Jid]): Unit = {
    }

    override def presenceChanged(presence: org.jivesoftware.smack.packet.Presence): Unit = {
      log.debug("User {} received presence changed info for {}", xmppSession.connection.getUser, presence.getFrom)
      xmppSession.sessionListener.presenceChanged(ContactInfo(XmppStringUtils.parseBareJid(presence.getFrom.asUnescapedString()), xmppSession.getTelaPresenceFromXMPPPresence(presence)))
    }
  }

  private class CallSignalPacketListener(private val xmppSession: SmackXMPPSession) extends StanzaListener {
    override def processStanza(packet: Stanza): Unit = {
      log.debug("User {} received call signal packet", xmppSession.connection.getUser)
      val signal = packet.asInstanceOf[CallSignal]
      xmppSession.sessionListener.callSignalReceived(signal.getFrom.asUnescapedString(), signal.data)
    }
  }

  private class TelaChatManagerListener(private val xmppSession: SmackXMPPSession) extends IncomingChatMessageListener {
    override def newIncomingMessage(from: EntityBareJid, message: Message, chat: Chat): Unit = {
      xmppSession.sessionListener.chatMessageReceived(from.asEntityBareJidString(), message.getBody)
    }
  }

  private[xmpp] class CallSignal(val data: String) extends IQ(CallSignalElementName, TelaURN) {
    override def getIQChildElementBuilder(xml: IQChildElementXmlStringBuilder): IQChildElementXmlStringBuilder = {
      xml.rightAngleBracket()
      xml.append(data)
      xml
    }
  }

  private[xmpp] class CallSignalProvider extends IQProvider[CallSignal] {
    override def parse(parser: XmlPullParser, initialDepth: Int): CallSignal = {
      parser.next()
      new CallSignal(parser.getText)
    }
  }
}

private[xmpp] class SmackXMPPSession(private val connection: XMPPConnection, private val sessionListener: XMPPSessionListener, private val xmppSettings: XMPPSettings) extends XMPPSession {
  override def disconnect(): Unit = {
    log.info("Disconnecting {}", connection.getUser)
    connection.asInstanceOf[AbstractXMPPConnection].disconnect()
  }

  override def setPresence(presence: tela.baseinterfaces.Presence): Unit = {
    log.info("User {} setting presence to {}", connection.getUser, presence)
    getXMPPPresenceFromTelaPresence(presence).foreach(presence => {
      import org.jivesoftware.smack.packet.Presence
      connection.sendStanza(new Presence(Presence.Type.available, DefaultStatusText, DefaultPriority, presence))
    })
  }

  override def changePassword(existingPassword: String, newPassword: String): Boolean = {
    changePassword(existingPassword, newPassword, createTCPConnectionToServer(xmppSettings))
  }

  override def getContactList(): Unit = {
    log.info("Retrieving contact list for user {}", connection.getUser)
    sessionListener.contactsAdded(Roster.getInstanceFor(connection).getEntries.asScala.map(
      entry => ContactInfo(entry.getJid.asUnescapedString(), getTelaPresenceFromXMPPPresence(Roster.getInstanceFor(connection).getPresence(entry.getJid)))).toArray.toVector)
  }

  override def addContact(address: String): Unit = {
    log.info("User {} adding contact {}", connection.getUser, address)
    Roster.getInstanceFor(connection).createEntry(JidCreate.bareFrom(address), address, Array[String]())
  }

  // BEGIN UNTESTED PUBSUB STUFF

  override def publish(nodeName: URI, content: String): Unit = {
    val manager = PubSubManager.getInstance(connection, connection.getUser.asBareJid())

    val node = getNode(manager, nodeName, connection.getUser.asUnescapedString()).getOrElse(createNode(manager, nodeName))

    log.info("User {} publishing to node {}", connection.getUser, node)
    val payload = new SimplePayload(content)
    val payloadItem = new PayloadItem(null, payload)
    node.publish(payloadItem)
  }

  override def getPublishedData(user: String, nodeName: URI): String = {
    val manager = PubSubManager.getInstance(connection, JidCreate.bareFrom(user))

    getNode(manager, nodeName, user).flatMap(node => {
      node.getItems[PayloadItem[SimplePayload]]().asScala.headOption.map(_.getPayload.toXML(null))
    }).getOrElse(<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"></rdf:RDF>.toString())
  }

  private def getNode(manager: PubSubManager, node: URI, user: String): Option[LeafNode] = {
    try {
      log.info("User {} attempting to retrieve node {} belonging to {}", connection.getUser, node, user)
      Some(manager.getNode[LeafNode](node.toString))
    } catch {
      case _: XMPPErrorException =>
        log.info("Node not found (XMPPErrorException)")
        None
      case _: AssertionError =>
        log.info("Node not found (AssertionError)")
        None
      case _: NotAPubSubNodeException =>
        log.info("Node not found (NotAPubSubNodeException)")
        None
    }
  }

  private def createNode(manager: PubSubManager, nodeName: URI): LeafNode = {
    log.info("User {} creating node {}", connection.getUser, nodeName)
    val config = new ConfigureForm(DataForm.Type.submit)
    config.setPersistentItems(true)
    config.setDeliverPayloads(true)
    config.setAccessModel(AccessModel.presence)
    config.setPublishModel(PublishModel.publishers)
    config.setMaxItems(1)
    config.setSubscribe(true)

    manager.createNode(nodeName.toString, config).asInstanceOf[LeafNode]
  }

  // END UNTESTED PUBSUB STUFF

  override def sendCallSignal(user: String, data: String): Unit = {
    log.info("User {} sending call signal to user {}", connection.getUser, user)
    val signal: CallSignal = new CallSignal(data)
    signal.setTo(JidCreate.fullFrom(if (XmppStringUtils.isFullJID(user)) user else s"$user/$DefaultResourceName"))
    signal.setType(Type.set)
    connection.sendStanza(signal)
  }

  override def sendChatMessage(user: String, message: String): Unit = {
    log.info("User {} sending chat message to user {}", connection.getUser, user)
    //TODO would probably be better to maintain a list of chat objects rather than creating a new one every time
    ChatManager.getInstanceFor(connection).chatWith(JidCreate.entityBareFrom(user)).send(message)
  }

  private def getXMPPPresenceFromTelaPresence(presence: baseinterfaces.Presence): Option[Mode] = {
    presence match {
      case tela.baseinterfaces.Presence.Available => Some(org.jivesoftware.smack.packet.Presence.Mode.available)
      case tela.baseinterfaces.Presence.Away => Some(org.jivesoftware.smack.packet.Presence.Mode.away)
      case tela.baseinterfaces.Presence.DoNotDisturb => Some(org.jivesoftware.smack.packet.Presence.Mode.dnd)
      case tela.baseinterfaces.Presence.Unknown => None
    }
  }

  private def getTelaPresenceFromXMPPPresence(presence: org.jivesoftware.smack.packet.Presence): tela.baseinterfaces.Presence = {
    if (presence.getType == org.jivesoftware.smack.packet.Presence.Type.available) {
      presence.getMode match {
        case org.jivesoftware.smack.packet.Presence.Mode.available => baseinterfaces.Presence.Available
        case org.jivesoftware.smack.packet.Presence.Mode.away => baseinterfaces.Presence.Away
        case org.jivesoftware.smack.packet.Presence.Mode.chat => baseinterfaces.Presence.Available
        case org.jivesoftware.smack.packet.Presence.Mode.dnd => baseinterfaces.Presence.DoNotDisturb
        case org.jivesoftware.smack.packet.Presence.Mode.xa => baseinterfaces.Presence.Away
        case null => baseinterfaces.Presence.Available
      }
    }
    else
      tela.baseinterfaces.Presence.Unknown
  }

  private def getPresenceForContact(contact: String): tela.baseinterfaces.Presence = {
    getTelaPresenceFromXMPPPresence(Roster.getInstanceFor(connection).getPresence(JidCreate.bareFrom(contact)))
  }

  private[xmpp] def changePassword(existingPassword: String, newPassword: String, changePasswordConnection: AbstractXMPPConnection): Boolean = {
    if (newPassword.isEmpty) {
      log.info("Rejecting blank password change request from user {}", connection.getUser)
      changePasswordConnection.disconnect()
      false
    }
    else if (connectToServer(XmppStringUtils.parseLocalpart(connection.getUser.asUnescapedString()), existingPassword, ChangePasswordResource, changePasswordConnection, xmppSettings, new StubSessionListener).isLeft) {
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
    override def contactsAdded(contacts: Vector[ContactInfo]): Unit = {}

    override def contactsRemoved(contacts: Vector[String]): Unit = {}

    override def presenceChanged(contact: ContactInfo): Unit = {}

    override def callSignalReceived(user: String, data: String): Unit = {}

    override def chatMessageReceived(user: String, message: String): Unit = {}
  }
}
