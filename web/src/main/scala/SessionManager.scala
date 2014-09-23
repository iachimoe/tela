package tela.web

import java.util.UUID

import akka.actor.{ActorLogging, ActorRef, Actor}
import tela.baseinterfaces._
import tela.web.JSONConversions.{PresenceUpdate, AddContacts, LanguageInfo}
import tela.web.SessionManager._
import tela.web.WebSocketDataPusher._

object SessionManager {
  case class Login(username: String, password: String, preferredLanguage: String)

  case class GetSession(sessionId: String)

  case class SetPresence(sessionId: String, presence: Presence)

  case class Logout(sessionId: String)

  case class GetLanguages(sessionId: String)

  case class RegisterWebSocket(sessionId: String, webSocketId: String)

  case class UnregisterWebSocket(sessionId: String, webSocketId: String)

  case class ChangePassword(sessionId: String, oldPassword: String, newPassword: String)

  case class SetLanguage(sessionId: String, language: String)

  case class GetContactList(sessionId: String)

  case class AddContact(sessionId: String, contact: String)

  private def generateRandomString: String = {
    UUID.randomUUID.toString
  }
}

class SessionManager(private val createConnection: (String, String, XMPPSessionListener) => Either[LoginFailure, XMPPSession],
                     private val languages: Map[String, String],
                     private val webSocketDataPusher: ActorRef,
                     private val generateSessionId: () => String = generateRandomString _) extends Actor with ActorLogging {

  private var sessions = Map[String, SessionInfo]()

  override def receive: Receive = {
    case Login(username, password, preferredLanguage) => attemptLogin(username, password, preferredLanguage)
    case GetSession(sessionId) => retrieveSessionIfItExists(sessionId)
    case SetPresence(sessionId, presence) => setPresence(sessionId, presence)
    case GetLanguages(sessionId) => sendLanguagesInfo(sessionId)
    case Logout(sessionId) => logout(sessionId)
    case RegisterWebSocket(sessionId, webSocketId) => registerWebSocket(sessionId, webSocketId)
    case UnregisterWebSocket(sessionId, webSocketId) => unregisterWebSocket(sessionId, webSocketId)
    case ChangePassword(sessionId, oldPassword, newPassword) => changePassword(sessionId, oldPassword, newPassword)
    case SetLanguage(sessionId, language) => setLanguage(sessionId, language)
    case GetContactList(sessionId) => getContactList(sessionId)
    case AddContact(sessionId, contact) => addContact(sessionId, contact)
  }

  def addContact(sessionId: String, contact: String): Unit = {
    log.debug("Adding contact {} for user with session {}", contact, sessionId)
    sessions(sessionId).xmppSession.addContact(contact)
  }

  def getContactList(sessionId: String): Unit = {
    log.debug("Getting contact list for user with session {}", sessionId)
    sessions(sessionId).xmppSession.getContactList
  }

  private def setLanguage(sessionId: String, language: String): Unit = {
    log.debug("Changing language for user with session {} to {}", sessionId, language)
    sessions += (sessionId -> sessions(sessionId).changeLanguage(language))
  }

  def changePassword(sessionId: String, oldPassword: String, newPassword: String) {
    log.debug("Attempting to change password for user with session {}", sessionId)
    webSocketDataPusher ! SendChangePasswordResult(sessions(sessionId).xmppSession.changePassword(oldPassword, newPassword), sessions(sessionId).webSockets)
  }

  private def unregisterWebSocket(sessionId: String, webSocketId: String): Unit = {
    log.debug("Unregistering web socket {} from session {}", webSocketId, sessionId)
    if (sessions.contains(sessionId)) {
      sessions += (sessionId -> sessions(sessionId).removeWebSocket(webSocketId))
    }
  }

  private def registerWebSocket(sessionId: String, webSocketId: String): Unit = {
    log.debug("Registering web socket {} for session {}", webSocketId, sessionId)
    if (sessions.contains(sessionId)) {
      sessions += (sessionId -> sessions(sessionId).addWebSocket(webSocketId))
      sender ! true
    }
    else sender ! false
  }

  private def sendLanguagesInfo(sessionId: String): Unit = {
    log.debug("Sending language info for session {}", sessionId)
    webSocketDataPusher ! SendLanguages(LanguageInfo(languages, sessions(sessionId).userData.language), sessions(sessionId).webSockets)
  }

  private def logout(sessionId: String): Unit = {
    val sessionInfo: SessionInfo = sessions(sessionId)
    log.debug("Logging out of session {} for user {}", sessionId, sessionInfo.userData.name)
    sessionInfo.xmppSession.disconnect()
    webSocketDataPusher ! CloseSockets(sessionInfo.webSockets)
    sessions -= sessionId
  }

  private def setPresence(sessionId: String, presence: Presence): Unit = {
    log.debug("Session {} changing presence to {}", sessionId, presence)
    sessions(sessionId).xmppSession.setPresence(presence)
  }

  private def retrieveSessionIfItExists(sessionId: String): Unit = {
    log.debug("Request for session {}", sessionId)
    sender ! sessions.get(sessionId).map(_.userData)
  }

  private def attemptLogin(username: String, password: String, preferredLanguage: String): Unit = {
    log.debug("Received login request for user {} with language {}", username, preferredLanguage)
    val listener: SessionListener = new SessionListener
    val connectionResult: Either[LoginFailure, String] = createConnection(username, password, listener).right.map(createNewSession(_, username, preferredLanguage))
    if (connectionResult.isRight) {
      listener.sessionId = connectionResult.right.get
    }
    sender ! connectionResult
  }

  private def createNewSession(session: XMPPSession, username: String, preferredLanguage: String): String =
  {
    val id = generateSessionId()
    sessions += (id -> new SessionInfo(session, new UserData(username, preferredLanguage)))
    id
  }

  private class SessionListener extends XMPPSessionListener
  {
    var sessionId: String = null

    override def contactsAdded(contacts: List[ContactInfo]): Unit = {
      webSocketDataPusher ! SendContactListInfo(AddContacts(contacts), sessions(sessionId).webSockets)
    }

    override def contactsRemoved(contacts: List[String]): Unit = {}

    override def presenceChanged(contact: ContactInfo): Unit = {
      webSocketDataPusher ! SendPresenceUpdate(PresenceUpdate(contact), sessions(sessionId).webSockets)
    }
  }
}
