package tela.web

import play.api.libs.json.{Json, Writes}
import tela.baseinterfaces.{ContactInfo, Presence}

object JSONConversions {
  val ActionKey = "action"
  val DataKey = "data"

  case class LanguageInfo(languages: Map[String, String], selected: String)

  val LanguagesKey = "languages"
  val SelectedLanguageKey = "selected"
  val LanguageKey = "language"

  implicit val languageInfoWrites: Writes[LanguageInfo] = new Writes[LanguageInfo] {
    def writes(languages: LanguageInfo) = Json.obj(
      LanguagesKey -> Json.toJsFieldJsValueWrapper(languages.languages),
      SelectedLanguageKey -> languages.selected)
  }

  val OldPasswordKey = "oldPassword"
  val NewPasswordKey = "newPassword"

  val ContactKey = "contact"
  val PresenceKey = "presence"

  val SetPresenceAction = "setPresence"
  val GetContactListAction = "getContactList"
  val AddContactAction = "addContact"

  case class AddContacts(contacts: Vector[ContactInfo])

  val AddContactsAction = "addContacts"

  implicit val addContactsWrites: Writes[AddContacts] = new Writes[AddContacts] {
    def writes(addContacts: AddContacts) = Json.obj(
      ActionKey -> AddContactsAction,
      DataKey -> Json.arr(addContacts.contacts.map {
        (info: ContactInfo) => Json.toJsFieldJsValueWrapper(Map(ContactKey -> info.jid, PresenceKey -> info.presence.toString.toLowerCase))
      }: _*))
  }

  case class PresenceUpdate(contact: ContactInfo)

  val PresenceUpdateAction = "presenceUpdate"

  implicit val presenceUpdateWrites: Writes[PresenceUpdate] = new Writes[PresenceUpdate] {
    def writes(presenceUpdate: PresenceUpdate) = Json.obj(
      ActionKey -> PresenceUpdateAction,
      DataKey -> Json.obj(
        ContactKey -> presenceUpdate.contact.jid,
        PresenceKey -> presenceUpdate.contact.presence.toString.toLowerCase)
    )
  }

  case class SelfPresenceUpdate(presence: Presence)

  val SelfPresenceUpdateAction = "selfPresenceUpdate"

  implicit val selfPresenceUpdateWrites: Writes[SelfPresenceUpdate] = new Writes[SelfPresenceUpdate] {
    def writes(presenceUpdate: SelfPresenceUpdate) = Json.obj(
      ActionKey -> SelfPresenceUpdateAction,
      DataKey -> Json.obj(
        PresenceKey -> presenceUpdate.presence.toString.toLowerCase)
    )
  }

  val SendCallSignalAction = "sendCallSignal"
  val CallSignalRecipientKey = "user"
  val CallSignalDataKey = "data"

  val CallSignalReceived = "callSignalReceived"
  val CallSignalSenderKey = "user"

  case class CallSignalReceipt(user: String, data: String)

  implicit val callSignalReceiptWrites: Writes[CallSignalReceipt] = new Writes[CallSignalReceipt] {
    override def writes(callSignalReceipt: CallSignalReceipt) = Json.obj(
      ActionKey -> CallSignalReceived,
      DataKey -> Json.obj(
        CallSignalSenderKey -> callSignalReceipt.user,
        CallSignalDataKey -> Json.toJsFieldJsValueWrapper(Json.parse(callSignalReceipt.data))
    ))
  }

  val SendChatMessageAction = "sendChatMessage"
  val ChatMessageRecipientKey = "user"

  val ChatMessageDataKey = "message"
  val ChatMessageReceived = "chatMessageReceived"
  val ChatMessageSenderKey = "user"

  case class ChatMessageReceipt(user: String, message: String)

  implicit val chatMessageReceiptWrites: Writes[ChatMessageReceipt] = new Writes[ChatMessageReceipt] {
    override def writes(chatMessageReceipt: ChatMessageReceipt) = Json.obj(
      ActionKey -> ChatMessageReceived,
      DataKey -> Json.obj(
        ChatMessageSenderKey -> chatMessageReceipt.user,
        ChatMessageDataKey -> chatMessageReceipt.message)
    )
  }
}
