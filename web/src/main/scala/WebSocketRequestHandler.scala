package tela.web

import akka.actor.ActorRef
import org.mashupbots.socko.events.WebSocketFrameEvent
import play.api.libs.json.Reads._
import play.api.libs.json._
import tela.baseinterfaces.Presence
import tela.web.JSONConversions._
import tela.web.SessionManager._

class WebSocketRequestHandler(sessionManager: ActorRef, private val closeWebSocket: (String) => Unit) extends RequestHandlerBase(sessionManager) {
  override def receive: Receive = {
    case wsFrame: WebSocketFrameEvent =>
      performActionDependingOnWhetherSessionExists(wsFrame.initialHttpRequest, (sessionId: String, userData: UserData) => {
        val json: JsValue = Json.parse(wsFrame.readText())
        getAction(json) match {
          case SetPresenceAction => sessionManager ! SetPresence(sessionId, Presence.getFromString((json \ DataKey).as[String]))
          case GetContactListAction => sessionManager ! GetContactList(sessionId)
          case AddContactAction => sessionManager ! AddContact(sessionId, (json \ DataKey).as[String])
          case SendCallSignalAction => sessionManager ! SendCallSignal(sessionId, (json \ DataKey \ CallSignalRecipientKey).as[String], (json \ DataKey \ CallSignalDataKey).toString())
        }
      }, () => closeWebSocket(wsFrame.webSocketId))
  }

  private def getAction(json: JsValue): String = {
    (json \ ActionKey).as[String]
  }

  override protected def getDocumentRoot: String = {
    null
  }
}
