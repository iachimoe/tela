package tela.web

import akka.actor.ActorRef
import akka.pattern.ask
import org.mashupbots.socko.events.WebSocketFrameEvent
import play.api.libs.json.Reads._
import play.api.libs.json._
import tela.baseinterfaces.Presence
import tela.web.SessionManager._
import tela.web.JSONConversions._

import scala.concurrent.Await

class WebSocketFrameHandler(private val sessionManager: ActorRef, private val closeWebSocket: (String) => Unit) extends RequestHandlerBase {
  override def receive: Receive = {
    case wsFrame: WebSocketFrameEvent =>
      getSessionIdFromCookie(wsFrame.initialHttpRequest) match {
        case Some(sessionId) =>
          implicit val timeout = createTimeout
          val future = sessionManager ? GetSession(sessionId)
          Await.result(future, timeout.duration).asInstanceOf[Option[String]] match {
            case Some(_) =>
              val json: JsValue = Json.parse(wsFrame.readText())
              getAction(json) match {
                case SetPresenceAction => sessionManager ! SetPresence(sessionId, Presence.getFromString((json \ DataKey).as[String]))
                case GetLanguagesAction => sessionManager ! GetLanguages(sessionId)
                case ChangePasswordAction => sessionManager ! ChangePassword(sessionId, (json \ DataKey \ OldPasswordKey).as[String], (json \ DataKey \ NewPasswordKey).as[String])
                case SetLanguageAction => sessionManager ! SetLanguage(sessionId, (json \ DataKey).as[String])
                case GetContactListAction => sessionManager ! GetContactList(sessionId)
                case AddContactAction => sessionManager ! AddContact(sessionId, (json \ DataKey).as[String])
              }
            case None => closeWebSocket(wsFrame.webSocketId)
          }
        case None => closeWebSocket(wsFrame.webSocketId)
      }
  }

  private def getAction(json: JsValue): String = {
    (json \ ActionKey).as[String]
  }

  override protected def getDocumentRoot: String = {
    null
  }
}
