import java.nio.file.{Path, Paths}
import java.util.UUID
import Module.*
import org.apache.pekko.actor.Props
import com.google.inject.AbstractModule
import com.google.inject.name.Names
import org.jivesoftware.smack.debugger.{JulDebugger, ReflectionDebuggerFactory}
import play.api.libs.concurrent.PekkoGuiceSupport
import play.api.libs.json.JsArray
import play.api.{Configuration, Environment}
import tela.baseinterfaces.{ComplexObject, DataStoreConnection, XMPPSession, XMPPSettings}
import tela.datastore.DataStoreConnectionImpl
import tela.web.{JsonFileHelper, SessionManager}
import tela.xmpp.SmackXMPPSession

import scala.concurrent.{ExecutionContext, Future}

object Module {
  case class DataStoreSettings(location: Path, genericFileDataMap: ComplexObject, dataMapping: Map[String, ComplexObject])

  private val AppRoot = Paths.get("apps")
  private val AppContainer = AppRoot.resolve("apps")
  private val AppIndexFile = AppRoot.resolve("appIndex.json")
  private val LanguagesFile = AppRoot.resolve("languages.json")
  private val MappingsFile = AppRoot.resolve("mappings.json")

  private val LoginPageRoot = Paths.get("mainpage")

  private val TikaConfigFileName = Paths.get("tika.xml")
  private val XMPPConfigKey = "xmpp-config"
  private val DataStoreConfigKey = "data-store-config"

  def loadXMPPSettings(configuration: Configuration): XMPPSettings = {
    XMPPSettings(configuration.get[String](XMPPConfigKey + ".hostname"),
      configuration.get[Int](XMPPConfigKey + ".port"),
      configuration.get[String](XMPPConfigKey + ".domain"),
      configuration.get[String](XMPPConfigKey + ".security-mode"),
      configuration.get[Boolean](XMPPConfigKey + ".debug"))
  }

  def loadDataStoreSettings(configuration: Configuration, mappingsFile: Path): DataStoreSettings = {
    import DataMappingReads._
    val mappingsJson = JsonFileHelper.getContents(mappingsFile)
    val genericFileDataMap = (mappingsJson \ "generic").as[ComplexObject]

    val dataMapping: Map[String, ComplexObject] = (mappingsJson \ "dataMappings").as[JsArray].value.toVector.flatMap(value => {
      val types = (value \ "types").as[Vector[String]]
      val mapping = (value \ "conf").as[ComplexObject]
      types.map(_ -> mapping)
    }).toMap

    DataStoreSettings(Paths.get(configuration.get[String](DataStoreConfigKey + ".root-directory")), genericFileDataMap, dataMapping)
  }

  def getSupportedLanguages(languagesFile: Path): Map[String, String] = {
    JsonFileHelper.getContents(languagesFile).as[Map[String, String]]
  }
}

class Module(environment: Environment, configuration: Configuration) extends AbstractModule with PekkoGuiceSupport {
  override def configure(): Unit = {
    // Perhaps this should be done in the xmpp component itself, but it feels more natural to control it at a high level.
    ReflectionDebuggerFactory.setDebuggerClass(classOf[JulDebugger])

    val xmppSettings = loadXMPPSettings(configuration)
    val dataStoreSettings = loadDataStoreSettings(configuration, MappingsFile)
    val supportedLanguages = getSupportedLanguages(LanguagesFile)

    bindActor[SessionManager]("session-manager", _ =>
      Props(classOf[SessionManager],
        SmackXMPPSession.connectToServer _,
        createDataStoreConnection(dataStoreSettings),
        supportedLanguages,
        xmppSettings,
        generateUUID _
      )
    )

    bind(classOf[Path]).annotatedWith(Names.named("apps-root-directory")).toInstance(AppContainer)
    bind(classOf[Path]).annotatedWith(Names.named("login-page-root")).toInstance(LoginPageRoot)
    bind(classOf[Path]).annotatedWith(Names.named("app-index-file")).toInstance(AppIndexFile)
  }

  private def createDataStoreConnection(dataStoreSettings: DataStoreSettings): (String, XMPPSession, ExecutionContext) => Future[DataStoreConnection] = {
    (user: String, xmppSession: XMPPSession, executionContext: ExecutionContext) => DataStoreConnectionImpl.getDataStore(
      dataStoreSettings.location,
      user,
      dataStoreSettings.genericFileDataMap,
      dataStoreSettings.dataMapping,
      xmppSession,
      TikaConfigFileName,
      generateUUID _,
      executionContext)
  }

  private def generateUUID(): UUID = {
    UUID.randomUUID()
  }
}
