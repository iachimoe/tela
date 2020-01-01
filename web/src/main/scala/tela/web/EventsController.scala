package tela.web

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.Materializer
import javax.inject.{Inject, Named}
import play.api.Logging
import play.api.libs.json.JsValue
import play.api.libs.streams.ActorFlow
import play.api.mvc.{Results, WebSocket}
import tela.web.SessionManager.RegisterWebSocket

import scala.concurrent.ExecutionContext

class EventsController @Inject()(@Named("session-manager") sessionManager: ActorRef)(
  implicit actorSystem: ActorSystem, val materializer: Materializer, val ec: ExecutionContext) extends Logging {
  def webSocket: WebSocket = WebSocket.acceptOrResult[JsValue, JsValue] { request =>
    getSessionFromRequest(request, sessionManager).map {
      case Some((sessionId, userData)) =>
        logger.info(s"Opening new websocket for ${userData.username}")
        Right(ActorFlow.actorRef(out => {
          sessionManager ! RegisterWebSocket(sessionId, out)
          Props(classOf[WebSocketRequestHandler], sessionManager, sessionId)
        }))
      case None => Left(Results.BadRequest)
    }
  }
}
