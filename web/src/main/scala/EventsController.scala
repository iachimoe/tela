package tela.web

import javax.inject.{Inject, Named}

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.Materializer
import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.JsValue
import play.api.libs.streams.ActorFlow
import play.api.mvc.{Results, WebSocket}
import tela.web.SessionManager.RegisterWebSocket

class EventsController @Inject()(@Named("session-manager") sessionManager: ActorRef)(implicit actorSystem: ActorSystem, materializer: Materializer) {
  def webSocket: WebSocket = WebSocket.acceptOrResult[JsValue, JsValue] { request =>
    getSessionFromRequest(request, sessionManager).map {
      case Some((sessionId, userData)) =>
        Logger.info(s"Opening new websocket for ${userData.username}")
        Right(ActorFlow.actorRef(out => {
          sessionManager ! RegisterWebSocket(sessionId, out)
          Props(classOf[WebSocketRequestHandler], sessionManager, sessionId)
        }))
      case None => Left(Results.BadRequest)
    }
  }
}
