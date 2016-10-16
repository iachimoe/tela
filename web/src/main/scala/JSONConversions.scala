package tela.web

import play.api.libs.json._
import tela.baseinterfaces.ContactInfo

object JSONConversions {
  val ActionKey = "action"
  val DataKey = "data"

  // LANGUAGES

  case class LanguageInfo(languages: Map[String, String], selected: String)

  val SetLanguagesAction = "setLanguages"
  val GetLanguagesAction = "getLanguages"
  val SetLanguageAction = "setLanguage"
  val LanguagesKey = "languages"
  val SelectedLanguageKey = "selected"
  val LanguageKey = "language"

  implicit val languageInfoWrites = new Writes[LanguageInfo] {
    def writes(languages: LanguageInfo) = Json.obj(
      LanguagesKey -> Json.toJsFieldJsValueWrapper(languages.languages),
      SelectedLanguageKey -> languages.selected)
  }

  // TEXT SEARCH
  val TextSearchResultsKey = "results"

  case class TextSearchResult(files: List[String])

  implicit val textSearchResultWrites = new Writes[TextSearchResult] {
    override def writes(result: TextSearchResult) = Json.obj(
      TextSearchResultsKey -> result.files
    )
  }

  // CHANGE PASSWORD
  val OldPasswordKey = "oldPassword"
  val NewPasswordKey = "newPassword"

  // CONTACTS/PRESENCE

  val ContactKey = "contact"
  val PresenceKey = "presence"

  val SetPresenceAction = "setPresence"
  val GetContactListAction = "getContactList"
  val AddContactAction = "addContact"

  case class AddContacts(contacts: List[ContactInfo])

  val AddContactsAction = "addContacts"

  implicit val addContactsWrites = new Writes[AddContacts] {
    def writes(addContacts: AddContacts) = Json.obj(
      ActionKey -> AddContactsAction,
      DataKey -> Json.arr(addContacts.contacts.map {
        (info: ContactInfo) => Json.toJsFieldJsValueWrapper(Map(ContactKey -> info.jid, PresenceKey -> info.presence.toString.toLowerCase))
      }: _*))
  }

  case class PresenceUpdate(contact: ContactInfo)

  val PresenceUpdateAction = "presenceUpdate"

  implicit val presenceUpdateWrites = new Writes[PresenceUpdate] {
    def writes(presenceUpdate: PresenceUpdate) = Json.obj(
      ActionKey -> PresenceUpdateAction,
      DataKey -> Json.obj(
        ContactKey -> presenceUpdate.contact.jid,
        PresenceKey -> presenceUpdate.contact.presence.toString.toLowerCase)
    )
  }

  val SendCallSignalAction = "sendCallSignal"
  val CallSignalRecipientKey = "user"
  val CallSignalDataKey = "data"

  val CallSignalReceived = "callSignalReceived"
  val CallSignalSenderKey = "user"

  case class CallSignalReceipt(user: String, data: String)

  implicit val callSignalReceiptWrites = new Writes[CallSignalReceipt] {
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

  implicit val chatMessageReceiptWrites = new Writes[ChatMessageReceipt] {
    override def writes(chatMessageReceipt: ChatMessageReceipt) = Json.obj(
      ActionKey -> ChatMessageReceived,
      DataKey -> Json.obj(
        ChatMessageSenderKey -> chatMessageReceipt.user,
        ChatMessageDataKey -> chatMessageReceipt.message)
    )
  }
}
