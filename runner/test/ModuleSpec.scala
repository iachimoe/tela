import java.net.URI
import java.nio.file.Paths

import Module.DataStoreSettings
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers._
import play.api.Configuration
import tela.baseinterfaces._

class ModuleSpec extends BaseSpec {
  "loadXMPPSettings" should "generate XMPPSettings object from config" in {
    val testConfig = Configuration(ConfigFactory.parseString("""xmpp-config {
                                                 |  hostname = "xmpp.example.com"
                                                 |  port = 5222
                                                 |  domain = "example.com"
                                                 |  security-mode = "enabled" // "required", "enabled" or "disabled"
                                                 |  debug = true
                                                 |}
                                                 |""".stripMargin))

    Module.loadXMPPSettings(testConfig) should === (XMPPSettings("xmpp.example.com", 5222, "example.com", "enabled", debug = true))
  }

  "loadDataStoreSettings" should "load location of data store from given config, and load mappings from given file" in {
    val testConfig = Configuration(ConfigFactory.parseString("""data-store-config {
                                                  |  root-directory = "/opt/datastore"
                                                  |}""".stripMargin))

    Module.loadDataStoreSettings(testConfig, getResource("mappings.json")) should ===(
      DataStoreSettings(Paths.get("/opt/datastore"),
        ComplexObject(new URI("http://schema.org/MediaObject"), Map(new URI("http://schema.org/alternateName") -> SimpleObject(Vector("hashPredicate"), DataType.Text))),
        Map(
          "text/plain" -> ComplexObject(new URI("http://schema.org/MediaObject"), Map(new URI("http://schema.org/name") -> SimpleObject(Vector("resourceName"), DataType.Text))),
          "audio/mpeg" -> ComplexObject(new URI("http://schema.org/AudioObject"), Map(new URI("http://schema.org/name") -> SimpleObject(Vector("dc:title"), DataType.Text)))
        )
      )
    )
  }

  "getSupportedLanguages" should "produce map based on languages given in config file" in {
    Module.getSupportedLanguages(getResource("languages.json")) should ===(
      Map("fr" -> "Français", "de" -> "Deutsch")
    )
  }

  private def getResource(filename: String) = {
    Paths.get(ClassLoader.getSystemResource(filename).toURI)
  }
}
