package tela.web

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill}
import play.api.libs.json.Json
import tela.baseinterfaces._
import tela.web.JSONConversions._
import tela.web.SessionManager._

object SessionManager {

  case class Login(username: String, password: String, preferredLanguage: String)

  case class GetSession(sessionId: String)

  case class SetPresence(sessionId: String, presence: Presence)

  case class Logout(sessionId: String)

  case class GetLanguages(sessionId: String)

  case class RegisterWebSocket(sessionId: String, webSocketActorRef: ActorRef)

  case class UnregisterWebSocket(sessionId: String, webSocketActorRef: ActorRef)

  case class ChangePassword(sessionId: String, oldPassword: String, newPassword: String)

  case class SetLanguage(sessionId: String, language: String)

  case class GetContactList(sessionId: String)

  case class AddContact(sessionId: String, contact: String)

  case class RetrieveData(sessionId: String, uri: String)

  case class RetrievePublishedData(sessionId: String, user: String, uri: String)

  case class PublishData(sessionId: String, json: String, uri: String)

  case class SendCallSignal(sessionId: String, user: String, data: String)

  case class SendChatMessage(sessionId: String, user: String, data: String)

  case class StoreMediaItem(sessionId: String, temporaryFileLocation: String, originalFileName: String)

  case class RetrieveMediaItem(sessionId: String, hash: String)

  case class SPARQLQuery(sessionId: String, query: String)

  case class TextSearch(sessionId: String, query: String)

  case class PushContactListInfoToWebSockets(sessionId: String, contacts: AddContacts)

  case class PushPresenceUpdateToWebSockets(sessionId: String, update: PresenceUpdate)

  case class PushCallSignalToWebSockets(sessionId: String, callSignalReceipt: CallSignalReceipt)

  case class PushChatMessageToWebSockets(sessionId: String, chatMessageReceipt: ChatMessageReceipt)
}

class SessionManager(createXMPPConnection: (String, String, XMPPSettings, XMPPSessionListener) => Either[LoginFailure, XMPPSession],
                     createDataStoreConnection: (String, XMPPSession) => DataStoreConnection,
                     languages: Map[String, String],
                     xmppSettings: XMPPSettings,
                     generateSessionId: () => String) extends Actor with ActorLogging {

  private var sessions = Map[String, SessionInfo]()

  override def receive: Receive = {
    //The following messages are expected to be sent from the controllers
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
    case PublishData(sessionId, json, uri) => publishData(sessionId, json, uri)
    case RetrieveData(sessionId, uri) => retrieveData(sessionId, uri)
    case RetrievePublishedData(sessionId, user, uri) => retrievePublishedData(sessionId, user, uri)
    case SendCallSignal(sessionId, user, data) => sendCallSignal(sessionId, user, data)
    case SendChatMessage(sessionId, user, message) => sendChatMessage(sessionId, user, message)
    case StoreMediaItem(sessionId, fileLocation, originalFileName) => storeMediaItem(sessionId, fileLocation, originalFileName)
    case RetrieveMediaItem(sessionId, hash) => retrieveMediaItem(sessionId, hash)
    case SPARQLQuery(sessionId, query) => runSPARQLQuery(sessionId, query)
    case TextSearch(sessionId, query) => runTextSearch(sessionId, query)
    //The following messages are expected to be received from the session listener
    case PushCallSignalToWebSockets(sessionId, callSignalReceipt) => pushCallSignalToWebSockets(sessionId, callSignalReceipt)
    case PushChatMessageToWebSockets(sessionId, chatMessageReceipt) => pushChatMessageToWebSockets(sessionId, chatMessageReceipt)
    case PushPresenceUpdateToWebSockets(sessionId, presenceUpdate) => pushPresenceUpdateToWebSockets(sessionId, presenceUpdate)
    case PushContactListInfoToWebSockets(sessionId, contacts) => pushContactListInfoToWebSockets(sessionId, contacts)
  }

  private def publishData(sessionId: String, json: String, uri: String): Unit = {
    log.debug("Publishing data for user with session {} with uri {}", sessionId, uri)
    sessions(sessionId).dataStoreConnection.insertJSON(json)
    sessions(sessionId).dataStoreConnection.publish(uri)
  }

  private def retrieveData(sessionId: String, uri: String): Unit = {
    log.debug("Requesting data for uri {} for user with session {}", uri, sessionId)
    sender ! sessions(sessionId).dataStoreConnection.retrieveJSON(uri)
  }

  private def runSPARQLQuery(sessionId: String, query: String): Unit = {
    log.debug("Running SPARQL query {} for user with session {}", query, sessionId)
    sender ! sessions(sessionId).dataStoreConnection.runSPARQLQuery(query)
  }

  private def runTextSearch(sessionId: String, query: String): Unit = {
    log.debug("Running text search {} for user with session {}", query, sessionId)
    sender ! TextSearchResult(sessions(sessionId).dataStoreConnection.textSearch(query))
  }

  private def retrievePublishedData(sessionId: String, user: String, uri: String): Unit = {
    log.debug("Requesting data for uri {} published by {} for user with session {}", uri, user, sessionId)
    sender ! sessions(sessionId).dataStoreConnection.retrievePublishedDataAsJSON(user, uri)
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

  def changePassword(sessionId: String, oldPassword: String, newPassword: String): Unit = {
    log.debug("Attempting to change password for user with session {}", sessionId)
    sender ! sessions(sessionId).xmppSession.changePassword(oldPassword, newPassword)
  }

  private def storeMediaItem(sessionId: String, fileLocation: String, originalFileName: String): Unit = {
    log.debug("Storing media item for user with session {}", sessionId)
    sessions(sessionId).dataStoreConnection.storeMediaItem(fileLocation, originalFileName)
  }

  private def retrieveMediaItem(sessionId: String, hash: String): Unit = {
    log.debug("Requesting media item with hash {} for user with session {}", hash, sessionId)
    sender ! sessions(sessionId).dataStoreConnection.retrieveMediaItem(hash)
  }

  private def unregisterWebSocket(sessionId: String, webSocketId: ActorRef): Unit = {
    log.debug("Unregistering web socket {} from session {}", webSocketId, sessionId)
    sessions.get(sessionId).foreach(session => sessions += (sessionId -> session.removeWebSocket(webSocketId)))
  }

  private def registerWebSocket(sessionId: String, webSocketId: ActorRef): Unit = {
    log.debug("Registering web socket {} for session {}", webSocketId, sessionId)
    sessions.get(sessionId).foreach(session => sessions += (sessionId -> session.addWebSocket(webSocketId)))
  }

  private def sendLanguagesInfo(sessionId: String): Unit = {
    log.debug("Sending language info for session {}", sessionId)
    sender ! LanguageInfo(languages, sessions(sessionId).userData.preferredLanguage)
  }

  private def logout(sessionId: String): Unit = {
    val sessionInfo: SessionInfo = sessions(sessionId)
    log.debug("Logging out of session {} for user {}", sessionId, sessionInfo.userData.username)

    //TODO prevent an exception from cutting this process short
    sessionInfo.xmppSession.disconnect()
    sessionInfo.dataStoreConnection.closeConnection()
    sessionInfo.webSockets.foreach(_ ! PoisonPill)
    sessions -= sessionId
  }

  private def setPresence(sessionId: String, presence: Presence): Unit = {
    log.debug("Session {} changing presence to {}", sessionId, presence)
    sessions(sessionId).xmppSession.setPresence(presence)
  }

  private def sendCallSignal(sessionId: String, user: String, data: String): Unit = {
    log.debug("Session {} changing sending call signal {} to {}", sessionId, data, user)
    sessions(sessionId).xmppSession.sendCallSignal(user, data)
  }

  private def sendChatMessage(sessionId: String, user: String, message: String): Unit = {
    log.debug("Session {} changing sending chat message {} to {}", sessionId, message, user)
    sessions(sessionId).xmppSession.sendChatMessage(user, message)
  }

  private def retrieveSessionIfItExists(sessionId: String): Unit = {
    val result = sessions.get(sessionId).map(_.userData)
    log.debug("Request for session {} yields result {}", sessionId, result)
    sender ! result
  }

  private def attemptLogin(username: String, password: String, preferredLanguage: String): Unit = {
    log.debug("Received login request for user {} with language {}", username, preferredLanguage)
    val sessionId = generateSessionId()
    val listener: SessionListener = new SessionListener(sessionId)

    //TODO createXMPPConnection is a blocking operation in practice....should be done in a separate thread pool?
    val connectionResult: Either[LoginFailure, XMPPSession] = createXMPPConnection(username, password, xmppSettings, listener)
    connectionResult.right.foreach(session => createNewSession(sessionId, session, createDataStoreConnection(username, session), username, preferredLanguage))
    val resultToSend = connectionResult.right.map(_ => sessionId)
    log.debug("Result of login request for user {}: {}", username, resultToSend)
    sender ! resultToSend
  }

  private def pushCallSignalToWebSockets(sessionId: String, callSignalReceipt: CallSignalReceipt): Unit = {
    sessions.get(sessionId).foreach(_.webSockets.foreach(webSocketActor => webSocketActor ! Json.toJson(callSignalReceipt)))
  }

  private def pushChatMessageToWebSockets(sessionId: String, chatMessageReceipt: ChatMessageReceipt): Unit = {
    sessions.get(sessionId).foreach(_.webSockets.foreach(webSocketActor => webSocketActor ! Json.toJson(chatMessageReceipt)))
  }

  private def pushPresenceUpdateToWebSockets(sessionId: String, presenceUpdate: PresenceUpdate): Unit = {
    sessions.get(sessionId).foreach(_.webSockets.foreach(webSocketActor => webSocketActor ! Json.toJson(presenceUpdate)))
  }

  private def pushContactListInfoToWebSockets(sessionId: String, contacts: AddContacts): Unit = {
    sessions.get(sessionId).foreach(_.webSockets.foreach(webSocketActor => webSocketActor ! Json.toJson(contacts)))
  }

  private def createNewSession(id: String, session: XMPPSession, dataStoreConnection: DataStoreConnection, username: String, preferredLanguage: String): Unit = {
    sessions += (id -> new SessionInfo(session, dataStoreConnection, UserData(username, preferredLanguage)))
  }

  private class SessionListener(sessionId: String) extends XMPPSessionListener {
    override def contactsAdded(contacts: List[ContactInfo]): Unit = {
      self ! PushContactListInfoToWebSockets(sessionId, AddContacts(contacts))
    }

    override def contactsRemoved(contacts: List[String]): Unit = {}

    override def presenceChanged(contact: ContactInfo): Unit = {
      self ! PushPresenceUpdateToWebSockets(sessionId, PresenceUpdate(contact))
    }

    override def callSignalReceived(user: String, data: String): Unit = {
      self ! PushCallSignalToWebSockets(sessionId, CallSignalReceipt(user, data))
    }

    override def chatMessageReceived(user: String, message: String): Unit = {
      self ! PushChatMessageToWebSockets(sessionId, ChatMessageReceipt(user, message))
    }
  }
}
