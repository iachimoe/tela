package tela.runner

import akka.actor.{ActorRef, ActorSystem, Props}
import com.typesafe.config.ConfigFactory
import org.mashupbots.socko.events.{HttpResponseStatus, SockoEvent}
import org.mashupbots.socko.routes._
import org.mashupbots.socko.webserver.{WebLogConfig, WebServer, WebServerConfig}
import play.api.libs.json.Json
import tela.web.SessionManager.{UnregisterWebSocket, RegisterWebSocket}
import tela.web._
import tela.xmpp.SmackXMPPSession

import scala.io.Source

object Tela {
  private val AppRoot = "runner/src/main/html"
  private val LoginPageRoot = "web/src/main/html"

  private var webServer: WebServer = null
  private var sessionManager: ActorRef = null

  def main(args: Array[String]) {
    val actorSystem = createActorSystem

    val webSocketFrameHandler = actorSystem.actorOf(Props(new WebSocketDataPusher(writeTextToSockets, closeSockets)))

    sessionManager = actorSystem.actorOf(Props(new SessionManager(SmackXMPPSession.connectToServer, getSupportedLanguages, webSocketFrameHandler)))

    startWebServer(actorSystem, configureWebServerRoutes(actorSystem))
  }

  private def closeWebSocket(webSocket: String): Unit = {
    webServer.webSocketConnections.close(webSocket)
  }

  private def writeTextToSockets(text: String, webSocketIds: Iterable[String]): Unit = {
    webServer.webSocketConnections.writeText(text, webSocketIds)
  }

  private def closeSockets(webSocketIds: Iterable[String]): Unit = {
    webServer.webSocketConnections.close(webSocketIds)
  }

  private def onWebSocketHandshakeComplete(sessionId: String) = {
    (webSocketId: String) => sessionManager ! RegisterWebSocket(sessionId, webSocketId)
  }

  private def onWebSocketClose(sessionId: String) = {
    (webSocketId: String) => sessionManager ! UnregisterWebSocket(sessionId, webSocketId)
  }

  private def getSupportedLanguages: Map[String, String] = {
    Json.parse(Source.fromFile(AppRoot + "/languages.json").mkString).as[Map[String, String]]
  }

  private def configureWebServerRoutes(actorSystem: ActorSystem): PartialFunction[SockoEvent, Unit] = {
    Routes({
      case HttpRequest(request) => request match {
        case Path("/") => actorSystem.actorOf(Props(new MainPageHandler(LoginPageRoot, sessionManager))) ! request
        case PathSegments("apps" :: relativePath :: theRest) => actorSystem.actorOf(Props(new AppHandler(AppRoot + "/apps", relativePath, sessionManager))) ! request
        case _ => request.response.write(HttpResponseStatus.NOT_FOUND)
      }
      case WebSocketHandshake(wsHandshake) => wsHandshake match {
        case Path("/events") =>
          val cookie: Option[String] = tela.web.getSessionIdFromCookie(wsHandshake.request)
          if (cookie.isDefined) {
            wsHandshake.authorize(onComplete = Some(onWebSocketHandshakeComplete(cookie.get)),
              onClose = Some(onWebSocketClose(cookie.get)))
          }
      }
      case WebSocketFrame(wsFrame) => actorSystem.actorOf(Props(new WebSocketFrameHandler(sessionManager, closeWebSocket))) ! wsFrame
    })
  }

  private def createActorSystem: ActorSystem = {
    val akkaConfig = """
      akka {
        event-handlers = ["akka.event.slf4j.Slf4jEventHandler"]
        loggers = ["akka.event.slf4j.Slf4jLogger"]
      }"""

    ActorSystem("ActorSystem", ConfigFactory.parseString(akkaConfig))
  }

  private def startWebServer(actorSystem: ActorSystem, routes: PartialFunction[SockoEvent, Unit]) {
    webServer = new WebServer(WebServerConfig(hostname = "0.0.0.0", webLog = Some(WebLogConfig())), routes, actorSystem)
    webServer.start()

    Runtime.getRuntime.addShutdownHook(new Thread {
      override def run() {
        webServer.stop()
      }
    })
  }
}
