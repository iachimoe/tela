import java.nio.file.{Path, Paths}
import java.util.UUID

import akka.actor.Props
import com.google.inject.AbstractModule
import com.google.inject.name.Names
import com.typesafe.config.Config
import play.api.libs.concurrent.AkkaGuiceSupport
import play.api.libs.json.JsArray
import play.api.{Configuration, Environment}
import tela.baseinterfaces.{ComplexObject, DataStoreConnection, XMPPSession, XMPPSettings}
import tela.datastore.DataStoreConnectionImpl
import tela.web.{JsonFileHelper, SessionManager}
import tela.xmpp.SmackXMPPSession

import Module._

object Module {
  case class DataStoreSettings(location: String, genericFileDataMap: ComplexObject, dataMapping: Map[String, ComplexObject])

  private val AppRoot = "apps"
  private val AppContainer = AppRoot + "/apps"
  private val AppIndexFile = AppRoot + "/appIndex.json"
  private val LanguagesFile = AppRoot + "/languages.json"
  private val MappingsFile = AppRoot + "/mappings.json"

  private val LoginPageRoot = "web/src/main/html"

  private val TikaConfigFileName = "tika.xml"
  private val XMPPConfigKey = "xmpp-config"
  private val DataStoreConfigKey = "data-store-config"

  def loadXMPPSettings(config: Config): XMPPSettings = {
    XMPPSettings(config.getString(XMPPConfigKey + ".hostname"),
      config.getInt(XMPPConfigKey + ".port"),
      config.getString(XMPPConfigKey + ".domain"),
      config.getString(XMPPConfigKey + ".security-mode"),
      config.getBoolean(XMPPConfigKey + ".debug"))
  }

  def loadDataStoreSettings(config: Config, mappingsFile: Path): DataStoreSettings = {
    import DataMappingReads._
    val mappingsJson = JsonFileHelper.getContents(mappingsFile)
    val genericFileDataMap = (mappingsJson \ "generic").as[ComplexObject]

    val dataMapping: Map[String, ComplexObject] = (mappingsJson \ "dataMappings").as[JsArray].value.toList.flatMap(value => {
      val types = (value \ "types").as[List[String]]
      val mapping = (value \ "conf").as[ComplexObject]
      types.map(_ -> mapping)
    }).toMap

    DataStoreSettings(config.getString(DataStoreConfigKey + ".root-directory"), genericFileDataMap, dataMapping)
  }

  def getSupportedLanguages(languagesFile: Path): Map[String, String] = {
    JsonFileHelper.getContents(languagesFile).as[Map[String, String]]
  }
}

class Module(environment: Environment, configuration: Configuration) extends AbstractModule with AkkaGuiceSupport {
  override def configure(): Unit = {
    val xmppSettings = loadXMPPSettings(configuration.underlying)
    //TODO APPARENTLY WE NEED TO CLOSE THESE SOURCE OBJECTS?!? ZOMG WE USE SOURCE ALL OVER THE PLACE!!!
    val dataStoreSettings = loadDataStoreSettings(configuration.underlying, Paths.get(MappingsFile))
    val supportedLanguages = getSupportedLanguages(Paths.get(LanguagesFile))

    bindActor[SessionManager]("session-manager", props =>
      Props(classOf[SessionManager],
        SmackXMPPSession.connectToServer _,
        createDataStoreConnection(dataStoreSettings),
        supportedLanguages,
        xmppSettings,
        generateUUID _
      )
    )

    bind(classOf[String]).annotatedWith(Names.named("apps-root-directory")).toInstance(AppContainer)
    bind(classOf[String]).annotatedWith(Names.named("login-page-root")).toInstance(LoginPageRoot)
    bind(classOf[String]).annotatedWith(Names.named("app-index-file")).toInstance(AppIndexFile)
  }

  private def createDataStoreConnection(dataStoreSettings: DataStoreSettings): (String, XMPPSession) => DataStoreConnection = {
    (user: String, xmppSession: XMPPSession) => DataStoreConnectionImpl.getDataStore(
      dataStoreSettings.location,
      user,
      dataStoreSettings.genericFileDataMap,
      dataStoreSettings.dataMapping,
      xmppSession,
      TikaConfigFileName,
      generateUUID _)
  }

  private def generateUUID: String = {
    UUID.randomUUID.toString
  }
}
