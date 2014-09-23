package tela.baseinterfaces

sealed trait LoginFailure

object LoginFailure
{
  case object InvalidCredentials extends LoginFailure
  case object ConnectionFailure extends LoginFailure
}

sealed abstract class Presence

object Presence
{
  case object Available extends Presence
  case object Away extends Presence
  case object DoNotDisturb extends Presence
  case object Unknown extends Presence

  private val values: List[Presence] = List[Presence](Available, Away, DoNotDisturb, Unknown)
  def getFromString(text: String) = values.find(_.toString.equalsIgnoreCase(text)).get
}

case class ContactInfo(jid: String, presence: Presence)

trait XMPPSessionListener {
  def contactsAdded(contacts: List[ContactInfo])

  def contactsRemoved(contacts: List[String])

  def presenceChanged(contact: ContactInfo)
}

trait XMPPSession {
  def disconnect(): Unit

  def setPresence(presence: Presence): Unit

  def changePassword(existingPassword: String, newPassword: String): Boolean

  def getContactList(): Unit

  def addContact(address: String)
}
