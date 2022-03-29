package tela.baseinterfaces

import java.net.URI

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
  def disconnect(): Unit

  def setPresence(presence: Presence): Unit

  def changePassword(existingPassword: String, newPassword: String): Boolean

  def getContactList(): Unit

  def addContact(address: String): Unit

  def publish(node: URI, content: String): Unit

  def getPublishedData(user: String, node: URI): String

  def sendCallSignal(user: String, data: String): Unit

  def sendChatMessage(user: String, message: String): Unit
}
