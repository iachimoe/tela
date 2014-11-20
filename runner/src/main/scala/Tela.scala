package tela.runner

import java.io.File

import akka.actor._
import com.typesafe.config.{Config, ConfigFactory}
import org.jivesoftware.smack.SmackConfiguration
import org.mashupbots.socko.events.{HttpResponseStatus, SockoEvent}
import org.mashupbots.socko.infrastructure.ConfigUtil
import org.mashupbots.socko.routes._
import org.mashupbots.socko.webserver.{WebServer, WebServerConfig}
import play.api.libs.json.Json
import tela.baseinterfaces.{DataStoreConnection, XMPPSession, XMPPSettings}
import tela.datastore.DataStoreConnectionImpl
import tela.web.SessionManager.{RegisterWebSocket, UnregisterWebSocket}
import tela.web._
import tela.xmpp.SmackXMPPSession

import scala.io.Source

object Tela {
  private val AppRoot = "runner/src/main/html"
  private val LoginPageRoot = "web/src/main/html"

  private val ConfigFileName = "tela.conf"
  private val WebServerConfigKey = "web-server-config"
  private val XMPPConfigKey = "xmpp-config"
  private val DataStoreConfigKey = "data-store-config"

  private var webServer: WebServer = null
  private var sessionManager: ActorRef = null

  def main(args: Array[String]) {
    val actorSystem = createActorSystem

    val webSocketFrameHandler = actorSystem.actorOf(Props(new WebSocketDataPusher(writeTextToSockets, closeSockets)))

    val xmppSettings = loadXMPPSettings(actorSystem)

    sessionManager = actorSystem.actorOf(Props(new SessionManager(
      SmackXMPPSession.connectToServer,
      createDataStoreConnection(loadDataStoreLocation(actorSystem)),
      getSupportedLanguages,
      xmppSettings,
      webSocketFrameHandler)))

    startWebServer(actorSystem, configureWebServerRoutes(actorSystem))
  }

  private def createDataStoreConnection(dataStoreLocation: String): (String, XMPPSession) => DataStoreConnection = {
    (user: String, xmppSession: XMPPSession) => DataStoreConnectionImpl.getDataStore(dataStoreLocation, user, xmppSession)
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
    //val staticContentHandlerRouter = actorSystem.actorOf(Props(new StaticContentHandler(new StaticContentHandlerConfig(rootFilePaths = Seq("/Users/will/dev/tela/web/src/main/static")))))

    Routes({
      case HttpRequest(request) => request match {
        case Path("/") => actorSystem.actorOf(Props(new MainPageHandler(sessionManager, LoginPageRoot, AppRoot + "/appIndex.json"))) ! request
        case Path("/data") => actorSystem.actorOf(Props(new DataHandler(sessionManager))) ! request
        case PathSegments("settings" :: setting :: theRest) => actorSystem.actorOf(Props(new SettingsDataHandler(sessionManager, setting))) ! request
        case PathSegments("apps" :: relativePath :: theRest) => actorSystem.actorOf(Props(new AppHandler(sessionManager, AppRoot + "/apps", relativePath))) ! request
        //case PathSegments("static" :: file :: Nil) => staticContentHandlerRouter ! new StaticFileRequest(request, new File("web/src/main/static", file))
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
      case WebSocketFrame(wsFrame) => actorSystem.actorOf(Props(new WebSocketRequestHandler(sessionManager, closeWebSocket))) ! wsFrame
    })
  }

  private def createActorSystem: ActorSystem = {
    ActorSystem("ActorSystem", ConfigFactory.parseFile(new File(ConfigFileName)))
  }

  private def startWebServer(actorSystem: ActorSystem, routes: PartialFunction[SockoEvent, Unit]) {
    object SockoServerConfig extends ExtensionId[WebServerConfig] with ExtensionIdProvider {
      override def lookup() = SockoServerConfig

      override def createExtension(system: ExtendedActorSystem) = new WebServerConfig(system.settings.config, WebServerConfigKey)
    }

    webServer = new WebServer(SockoServerConfig(actorSystem), routes, actorSystem)
    webServer.start()

    Runtime.getRuntime.addShutdownHook(new Thread {
      override def run() {
        webServer.stop()
      }
    })
  }

  private def loadDataStoreLocation(actorSystem: ActorSystem): String = {
    class DataStoreSettings(val rootDirectory: String) extends Extension {
      def this(config: Config, prefix: String) = this(
        ConfigUtil.getString(config, DataStoreConfigKey + ".root-directory", null)
      )
    }

    object DataStoreConfig extends ExtensionId[DataStoreSettings] with ExtensionIdProvider {
      override def lookup() = DataStoreConfig

      override def createExtension(system: ExtendedActorSystem) = new DataStoreSettings(system.settings.config, WebServerConfigKey)
    }

    DataStoreConfig(actorSystem).rootDirectory
  }

  private def loadXMPPSettings(actorSystem: ActorSystem): XMPPSettings = {
    class AkkaXMPPSettings(val hostname: String, val port: Int, val domain: String, val securityMode: String, val debug: Boolean) extends Extension {
      def this(config: Config, prefix: String) = this(
        ConfigUtil.getString(config, XMPPConfigKey + ".hostname", null),
        ConfigUtil.getInt(config, XMPPConfigKey + ".port", 5222),
        ConfigUtil.getString(config, XMPPConfigKey + ".domain", null),
        ConfigUtil.getString(config, XMPPConfigKey + ".security-mode", "disabled"),
        ConfigUtil.getBoolean(config, XMPPConfigKey + ".debug", false)
      )
    }

    object XMPPConfig extends ExtensionId[AkkaXMPPSettings] with ExtensionIdProvider {
      override def lookup() = XMPPConfig

      override def createExtension(system: ExtendedActorSystem) = new AkkaXMPPSettings(system.settings.config, WebServerConfigKey)
    }

    val akkaSettings = XMPPConfig(actorSystem)

    //TODO: This method shouldn't have a side effect...
    if (akkaSettings.debug) {
      SmackConfiguration.DEBUG_ENABLED = true
    }

    XMPPSettings(akkaSettings.hostname, akkaSettings.port, akkaSettings.domain, akkaSettings.securityMode)
  }
}
