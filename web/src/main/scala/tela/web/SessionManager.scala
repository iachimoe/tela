package tela.web

import java.net.URI
import java.nio.file.Path
import java.util.UUID
import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, PoisonPill}
import org.apache.pekko.pattern.pipe
import play.api.libs.json.{Json, Writes}
import tela.baseinterfaces._
import tela.web.JSONConversions._
import tela.web.SessionManager._

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

object SessionManager {
  case class HandleLoginResult(result: Either[LoginFailure, (UUID, SessionInfo)], respondTo: ActorRef)

  case class Login(username: String, password: String, preferredLanguage: String)

  case class GetSession(sessionId: UUID)

  case class SetPresence(sessionId: UUID, presence: Presence)

  case class Logout(sessionId: UUID)

  case class GetLanguages(sessionId: UUID)

  case class RegisterWebSocket(sessionId: UUID, webSocketActorRef: ActorRef)

  case class UnregisterWebSocket(sessionId: UUID, webSocketActorRef: ActorRef)

  case class ChangePassword(sessionId: UUID, oldPassword: String, newPassword: String)

  case class SetLanguage(sessionId: UUID, language: String)

  case class GetContactList(sessionId: UUID)

  case class AddContact(sessionId: UUID, contact: String)

  case class RetrieveData(sessionId: UUID, uri: URI)

  case class RetrievePublishedData(sessionId: UUID, user: String, uri: URI)

  case class PublishData(sessionId: UUID, json: String, uri: URI)

  case class SendCallSignal(sessionId: UUID, user: String, data: String)

  case class SendChatMessage(sessionId: UUID, user: String, data: String)

  case class StoreMediaItem(sessionId: UUID, temporaryFileLocation: Path, originalFileName: Path, lastModified: Option[LocalDateTime])

  case class RetrieveMediaItem(sessionId: UUID, hash: String)

  case class SPARQLQuery(sessionId: UUID, query: String)

  case class PushContactListInfoToWebSockets(sessionId: UUID, contacts: AddContacts)

  case class PushPresenceUpdateToWebSockets(sessionId: UUID, update: PresenceUpdate)

  case class PushSelfPresenceUpdateToWebSockets(sessionId: UUID, update: SelfPresenceUpdate)

  case class PushCallSignalToWebSockets(sessionId: UUID, callSignalReceipt: CallSignalReceipt)

  case class PushChatMessageToWebSockets(sessionId: UUID, chatMessageReceipt: ChatMessageReceipt)
}

class SessionManager(createXMPPConnection: (String, String, XMPPSettings, XMPPSessionListener, ExecutionContext) => Future[Either[LoginFailure, XMPPSession]],
                     createDataStoreConnection: (String, XMPPSession, ExecutionContext) => Future[DataStoreConnection],
                     languages: Map[String, String],
                     xmppSettings: XMPPSettings,
                     generateSessionId: () => UUID) extends Actor with ActorLogging {

  private var sessions = Map[UUID, SessionInfo]()

  // DataStoreConnection and XMPPSession do a lot of blocking operations.
  // We're passing this execution context to them when we instantiate them.
  // It would be preferable to do this when setting up the GuiceModule for the app,
  // but that is not made easy by the Play framework's plumbing.
  // Note that we also import context.dispatcher here, so that the default dispatcher will be used when
  // the actor needs an execution context for its own operations.
  private val blockingExecutionContext = context.system.dispatchers.lookup("blocking-operations-dispatcher")
  import context.dispatcher

  override def receive: Receive = {
    case HandleLoginResult(result, respondTo) => handleLoginResult(result, respondTo)
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
    case StoreMediaItem(sessionId, fileLocation, originalFileName, lastModified) => storeMediaItem(sessionId, fileLocation, originalFileName, lastModified)
    case RetrieveMediaItem(sessionId, hash) => retrieveMediaItem(sessionId, hash)
    case SPARQLQuery(sessionId, query) => runSPARQLQuery(sessionId, query)
    //The following messages are expected to be received from the session listener
    case PushCallSignalToWebSockets(sessionId, callSignalReceipt) => pushCallSignalToWebSockets(sessionId, callSignalReceipt)
    case PushChatMessageToWebSockets(sessionId, chatMessageReceipt) => pushChatMessageToWebSockets(sessionId, chatMessageReceipt)
    case PushPresenceUpdateToWebSockets(sessionId, presenceUpdate) => pushPresenceUpdateToWebSockets(sessionId, presenceUpdate)
    case PushSelfPresenceUpdateToWebSockets(sessionId, presenceUpdate) => pushSelfPresenceUpdateToWebSockets(sessionId, presenceUpdate)
    case PushContactListInfoToWebSockets(sessionId, contacts) => pushContactListInfoToWebSockets(sessionId, contacts)
  }

  private def publishData(sessionId: UUID, json: String, uri: URI): Unit = {
    log.debug("Publishing data for user with session {} with uri {}", sessionId, uri)
    val session = sessions(sessionId)
    session.dataStoreConnection.insertJSON(json).flatMap(_ => session.dataStoreConnection.publish(uri))
    ()
  }

  private def retrieveData(sessionId: UUID, uri: URI): Unit = {
    log.debug("Requesting data for uri {} for user with session {}", uri, sessionId)
    sessions(sessionId).dataStoreConnection.retrieveJSON(uri) pipeTo sender()
    ()
  }

  private def runSPARQLQuery(sessionId: UUID, query: String): Unit = {
    log.debug("Running SPARQL query {} for user with session {}", query, sessionId)
    sessions(sessionId).dataStoreConnection.runSPARQLQuery(query) pipeTo sender()
    ()
  }

  private def retrievePublishedData(sessionId: UUID, user: String, uri: URI): Unit = {
    log.debug("Requesting data for uri {} published by {} for user with session {}", uri, user, sessionId)
    sessions(sessionId).dataStoreConnection.retrievePublishedDataAsJSON(user, uri) pipeTo sender()
    ()
  }

  private def addContact(sessionId: UUID, contact: String): Unit = {
    log.debug("Adding contact {} for user with session {}", contact, sessionId)
    sessions(sessionId).xmppSession.addContact(contact)
    ()
  }

  private def getContactList(sessionId: UUID): Unit = {
    log.debug("Getting contact list for user with session {}", sessionId)
    sessions(sessionId).xmppSession.getContactList()
    ()
  }

  private def setLanguage(sessionId: UUID, language: String): Unit = {
    log.debug("Changing language for user with session {} to {}", sessionId, language)
    sessions += (sessionId -> sessions(sessionId).changeLanguage(language))
  }

  private def changePassword(sessionId: UUID, oldPassword: String, newPassword: String): Unit = {
    log.debug("Attempting to change password for user with session {}", sessionId)
    sessions(sessionId).xmppSession.changePassword(oldPassword, newPassword) pipeTo sender()
    ()
  }

  private def storeMediaItem(sessionId: UUID, fileLocation: Path, originalFileName: Path, lastModified: Option[LocalDateTime]): Unit = {
    log.debug("Storing media item for user with session {}", sessionId)
    sessions(sessionId).dataStoreConnection.storeMediaItem(fileLocation, originalFileName, lastModified)
    ()
  }

  private def retrieveMediaItem(sessionId: UUID, hash: String): Unit = {
    log.debug("Requesting media item with hash {} for user with session {}", hash, sessionId)
    sessions(sessionId).dataStoreConnection.retrieveMediaItem(hash) pipeTo sender()
    ()
  }

  private def unregisterWebSocket(sessionId: UUID, webSocketId: ActorRef): Unit = {
    log.debug("Unregistering web socket {} from session {}", webSocketId, sessionId)
    sessions.get(sessionId).foreach(session => sessions += (sessionId -> session.removeWebSocket(webSocketId)))
  }

  private def registerWebSocket(sessionId: UUID, webSocketId: ActorRef): Unit = {
    log.debug("Registering web socket {} for session {}", webSocketId, sessionId)
    sessions.get(sessionId).foreach(session => sessions += (sessionId -> session.addWebSocket(webSocketId)))
  }

  private def sendLanguagesInfo(sessionId: UUID): Unit = {
    log.debug("Sending language info for session {}", sessionId)
    sender() ! LanguageInfo(languages, sessions(sessionId).userData.preferredLanguage)
  }

  private def logout(sessionId: UUID): Unit = {
    val sessionInfo: SessionInfo = sessions(sessionId)
    log.debug("Logging out of session {} for user {}", sessionId, sessionInfo.userData.username)

    sessionInfo.webSockets.foreach(_ ! PoisonPill)
    sessions -= sessionId
    sessionInfo.xmppSession.disconnect()
    sessionInfo.dataStoreConnection.closeConnection()
    ()
  }

  private def setPresence(sessionId: UUID, presence: Presence): Unit = {
    log.debug("Session {} changing presence to {}", sessionId, presence)
    sessions(sessionId).xmppSession.setPresence(presence)
    ()
  }

  private def sendCallSignal(sessionId: UUID, user: String, data: String): Unit = {
    log.debug("Session {} sending call signal {} to {}", sessionId, data, user)
    sessions(sessionId).xmppSession.sendCallSignal(user, data)
    ()
  }

  private def sendChatMessage(sessionId: UUID, user: String, message: String): Unit = {
    log.debug("Session {} sending chat message {} to {}", sessionId, message, user)
    sessions(sessionId).xmppSession.sendChatMessage(user, message)
    ()
  }

  private def retrieveSessionIfItExists(sessionId: UUID): Unit = {
    val result = sessions.get(sessionId).map(_.userData)
    log.debug("Request for session {} yields result {}", sessionId, result)
    sender() ! result
  }

  private def handleLoginResult(result: Either[LoginFailure, (UUID, SessionInfo)], respondTo: ActorRef): Unit = {
    result.foreach {
      case (sessionId, sessionInfo) => sessions += (sessionId -> sessionInfo)
    }
    val resultToSend = result.map(_._1)
    log.debug("Result of login request: {}", resultToSend)
    respondTo ! resultToSend
  }

  private def attemptLogin(username: String, password: String, preferredLanguage: String): Unit = {
    log.debug("Received login request for user {} with language {}", username, preferredLanguage)
    val sessionId = generateSessionId()
    val listener: SessionListener = new SessionListener(sessionId)

    val respondTo = sender()
    createXMPPConnection(username, password, xmppSettings, listener, blockingExecutionContext).flatMap {
      case Left(loginFailure) => Future.successful(Left(loginFailure))
      case Right(xmppSession) =>
        createDataStoreConnection(username, xmppSession, blockingExecutionContext).map((dataStoreConnection: DataStoreConnection) =>
          Right(sessionId -> new SessionInfo(xmppSession, dataStoreConnection, UserData(username, preferredLanguage))))
    } map { result => HandleLoginResult(result, respondTo) } pipeTo self
    ()
  }

  private def pushCallSignalToWebSockets(sessionId: UUID, callSignalReceipt: CallSignalReceipt): Unit = {
    sendMessageAsJSONToWebsockets(sessionId, callSignalReceipt)
  }

  private def pushChatMessageToWebSockets(sessionId: UUID, chatMessageReceipt: ChatMessageReceipt): Unit = {
    sendMessageAsJSONToWebsockets(sessionId, chatMessageReceipt)
  }

  private def pushPresenceUpdateToWebSockets(sessionId: UUID, presenceUpdate: PresenceUpdate): Unit = {
    sendMessageAsJSONToWebsockets(sessionId, presenceUpdate)
  }

  private def pushSelfPresenceUpdateToWebSockets(sessionId: UUID, selfPresenceUpdate: SelfPresenceUpdate): Unit = {
    sendMessageAsJSONToWebsockets(sessionId, selfPresenceUpdate)
  }

  private def pushContactListInfoToWebSockets(sessionId: UUID, contacts: AddContacts): Unit = {
    sendMessageAsJSONToWebsockets(sessionId, contacts)
  }

  private def sendMessageAsJSONToWebsockets[T](sessionId: UUID, message: T)(implicit jsonConverter: Writes[T]): Unit = {
    sessions.get(sessionId).foreach(_.webSockets.foreach(webSocketActor => webSocketActor ! Json.toJson(message)))
  }

  private class SessionListener(sessionId: UUID) extends XMPPSessionListener {
    override def contactsAdded(contacts: Vector[ContactInfo]): Unit = {
      self ! PushContactListInfoToWebSockets(sessionId, AddContacts(contacts))
    }

    override def contactsRemoved(contacts: Vector[String]): Unit = {}

    override def presenceChanged(contact: ContactInfo): Unit = {
      self ! PushPresenceUpdateToWebSockets(sessionId, PresenceUpdate(contact))
    }

    override def selfPresenceChanged(presence: Presence): Unit = {
      self ! PushSelfPresenceUpdateToWebSockets(sessionId, SelfPresenceUpdate(presence))
    }

    override def callSignalReceived(user: String, data: String): Unit = {
      self ! PushCallSignalToWebSockets(sessionId, CallSignalReceipt(user, data))
    }

    override def chatMessageReceived(user: String, message: String): Unit = {
      self ! PushChatMessageToWebSockets(sessionId, ChatMessageReceipt(user, message))
    }
  }
}
