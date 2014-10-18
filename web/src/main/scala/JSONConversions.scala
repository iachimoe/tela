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

  implicit val languageInfoWrites = new Writes[LanguageInfo] {
    def writes(languages: LanguageInfo) = Json.obj(
      ActionKey -> SetLanguagesAction,
      DataKey -> Json.obj(
        LanguagesKey -> Json.toJsFieldJsValueWrapper(languages.languages),
        SelectedLanguageKey -> languages.selected)
    )
  }

  // CHANGE PASSWORD
  val ChangePasswordAction = "changePassword"
  val ChangePasswordSucceeded = "changePasswordSucceeded"
  val ChangePasswordFailed = "changePasswordFailed"
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
      }.toSeq: _*))
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
}
