package tela.runner

import java.io.File

import akka.actor._
import com.typesafe.config._
import org.jivesoftware.smack.SmackConfiguration
import org.mashupbots.socko.events.{HttpResponseStatus, SockoEvent}
import org.mashupbots.socko.handlers.{StaticContentHandler, StaticContentHandlerConfig, StaticFileRequest}
import org.mashupbots.socko.routes._
import org.mashupbots.socko.webserver.{WebServer, WebServerConfig}
import play.api.libs.json.{JsArray, Json}
import tela.baseinterfaces._
import tela.datastore.DataStoreConnectionImpl
import tela.web.SessionManager.{RegisterWebSocket, UnregisterWebSocket}
import tela.web._
import tela.xmpp.SmackXMPPSession

import scala.io.Source

object Tela {
  case class DataStoreSettings(location: String, genericFileDataMap: ComplexObject, dataMapping: Map[String, ComplexObject])

  private val AppRoot = "runner/src/main/html"
  private val LoginPageRoot = "web/src/main/html"
  private val StaticContentRoot = "web/src/main/static"

  private val ConfigFileName = "tela.conf"
  private val TikaConfigFileName = "tika.xml"
  private val WebServerConfigKey = "web-server-config"
  private val XMPPConfigKey = "xmpp-config"
  private val DataStoreConfigKey = "data-store-config"

  private var webServer: WebServer = null
  private var sessionManager: ActorRef = null

  def main(args: Array[String]): Unit = {
    val actorSystem = createActorSystem

    val webSocketDataPusher = actorSystem.actorOf(Props(new WebSocketDataPusher(writeTextToSockets, closeSockets)))

    val xmppSettings = loadXMPPSettings(actorSystem.settings.config)

    sessionManager = actorSystem.actorOf(Props(new SessionManager(
      SmackXMPPSession.connectToServer,
      createDataStoreConnection(loadDataStoreSettings(actorSystem.settings.config)),
      getSupportedLanguages,
      xmppSettings,
      webSocketDataPusher)))

    startWebServer(actorSystem, configureWebServerRoutes(actorSystem))
  }

  private def createDataStoreConnection(dataStoreSettings: DataStoreSettings): (String, XMPPSession) => DataStoreConnection = {
    (user: String, xmppSession: XMPPSession) => DataStoreConnectionImpl.getDataStore(
      dataStoreSettings.location,
      user,
      dataStoreSettings.genericFileDataMap,
      dataStoreSettings.dataMapping,
      xmppSession,
      TikaConfigFileName)
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
    val staticContentHandlerRouter = actorSystem.actorOf(Props(new StaticContentHandler(StaticContentHandlerConfig(rootFilePaths = Seq(new File(StaticContentRoot).getAbsolutePath)))))

    Routes({
      case HttpRequest(request) => request match {
        case Path("/") => actorSystem.actorOf(Props(new MainPageHandler(sessionManager, LoginPageRoot, AppRoot + "/appIndex.json"))) ! request
        case Path("/data") => actorSystem.actorOf(Props(new DataHandler(sessionManager))) ! request
        case PathSegments("settings" :: setting :: theRest) => actorSystem.actorOf(Props(new SettingsDataHandler(sessionManager, setting))) ! request
        case PathSegments("apps" :: relativePath :: theRest) => actorSystem.actorOf(Props(new AppHandler(sessionManager, AppRoot + "/apps", relativePath))) ! request
        case PathSegments("webjars" :: file :: theRest) => actorSystem.actorOf(Props(new WebJarHandler(file))) ! request
        case PathSegments("static" :: path) => staticContentHandlerRouter ! StaticFileRequest(request, new File(StaticContentRoot, path.foldLeft("")((a, b) => a + "/" + b)))
        case _ => request.response.write(HttpResponseStatus.NOT_FOUND)
      }
      case WebSocketHandshake(wsHandshake) => wsHandshake match {
        case Path("/events") =>
          tela.web.getSessionIdFromCookie(wsHandshake.request).foreach(sessionId => {
            wsHandshake.authorize(
              onComplete = Some(onWebSocketHandshakeComplete(sessionId)),
              onClose = Some(onWebSocketClose(sessionId)))
          })
      }
      case WebSocketFrame(wsFrame) => actorSystem.actorOf(Props(new WebSocketRequestHandler(sessionManager, closeWebSocket))) ! wsFrame
    })
  }

  private def createActorSystem: ActorSystem = {
    ActorSystem("ActorSystem", ConfigFactory.parseFile(new File(ConfigFileName)).resolve())
  }

  private def startWebServer(actorSystem: ActorSystem, routes: PartialFunction[SockoEvent, Unit]): Unit = {
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

  private def loadDataStoreSettings(config: Config): DataStoreSettings = {
    import DataMappingReads._
    val mappingsJson = Json.parse(Source.fromFile(AppRoot + "/mappings.json").mkString)
    val genericFileDataMap = (mappingsJson \ "generic").as[ComplexObject]

    val dataMapping: Map[String, ComplexObject] = (mappingsJson \ "dataMappings").as[JsArray].value.toList.flatMap(value => {
      val types = (value \ "types").as[List[String]]
      val mapping = (value \ "conf").as[ComplexObject]
      types.map(_ -> mapping)
    }).toMap

    DataStoreSettings(config.getString(DataStoreConfigKey + ".root-directory"), genericFileDataMap, dataMapping)
  }

  private def loadXMPPSettings(config: Config): XMPPSettings = {
    //TODO: This method shouldn't have a side effect...
    if (config.getBoolean(XMPPConfigKey + ".debug")) {
      SmackConfiguration.DEBUG = true
    }

    XMPPSettings(config.getString(XMPPConfigKey + ".hostname"),
      config.getInt(XMPPConfigKey + ".port"),
      config.getString(XMPPConfigKey + ".domain"),
      config.getString(XMPPConfigKey + ".security-mode"))
  }
}
