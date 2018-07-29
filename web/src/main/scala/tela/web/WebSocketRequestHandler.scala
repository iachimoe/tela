package tela.web

import java.util.UUID

import akka.actor.{Actor, ActorRef}
import play.api.Logger
import play.api.libs.json.Reads._
import play.api.libs.json._
import tela.baseinterfaces.Presence
import tela.web.JSONConversions._
import tela.web.SessionManager._

class WebSocketRequestHandler(sessionManager: ActorRef, sessionId: UUID) extends Actor {
  override def receive: Receive = {
    case json: JsValue => getAction(json) match {
      case SetPresenceAction => sessionManager ! SetPresence(sessionId, Presence.getFromString((json \ DataKey).as[String]))
      case GetContactListAction => sessionManager ! GetContactList(sessionId)
      case AddContactAction => sessionManager ! AddContact(sessionId, (json \ DataKey).as[String])
      case SendCallSignalAction => sessionManager ! SendCallSignal(sessionId, (json \ DataKey \ CallSignalRecipientKey).as[String], (json \ DataKey \ CallSignalDataKey).as[JsValue].toString())
      case SendChatMessageAction => sessionManager ! SendChatMessage(sessionId, (json \ DataKey \ ChatMessageRecipientKey).as[String], (json \ DataKey \ ChatMessageDataKey).as[String])
    }
  }

  override def postStop(): Unit = {
    Logger.info(s"Websocket for $sessionId closing")
    sessionManager ! UnregisterWebSocket(sessionId, self)
  }

  private def getAction(json: JsValue): String = {
    (json \ ActionKey).as[String]
  }
}
