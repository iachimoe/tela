package tela.baseinterfaces

import java.net.URI
import scala.concurrent.Future

case class XMPPSettings(hostname: String, port: Int, domain: String, securityMode: String, debug: Boolean)

sealed trait LoginFailure

object LoginFailure {

  case object InvalidCredentials extends LoginFailure

  case object ConnectionFailure extends LoginFailure

}

sealed trait Presence

object Presence {

  case object Available extends Presence

  case object Away extends Presence

  case object DoNotDisturb extends Presence

  case object Unknown extends Presence

  private val values: Vector[Presence] = Vector[Presence](Available, Away, DoNotDisturb, Unknown)

  def getFromString(text: String) = values.find(_.toString.equalsIgnoreCase(text)).get
}

case class ContactInfo(jid: String, presence: Presence)

trait XMPPSessionListener {
  def contactsAdded(contacts: Vector[ContactInfo]): Unit

  def contactsRemoved(contacts: Vector[String]): Unit

  def presenceChanged(contact: ContactInfo): Unit

  def selfPresenceChanged(presence: Presence): Unit

  def callSignalReceived(user: String, data: String): Unit

  def chatMessageReceived(user: String, message: String): Unit
}

trait XMPPSession {
  def disconnect(): Future[Unit]

  def setPresence(presence: Presence): Future[Unit]

  def changePassword(existingPassword: String, newPassword: String): Future[Boolean]

  def getContactList(): Future[Unit]

  def addContact(address: String): Future[Unit]

  def publish(node: URI, content: String): Future[Unit]

  def getPublishedData(user: String, node: URI): Future[String]

  def sendCallSignal(user: String, data: String): Future[Unit]

  def sendChatMessage(user: String, message: String): Future[Unit]
}
