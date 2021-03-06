package tela.baseinterfaces

import java.net.URI

import org.scalactic.TypeCheckedTripleEquals
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatestplus.mockito.MockitoSugar

trait BaseSpec extends AnyFlatSpec with TypeCheckedTripleEquals with MockitoSugar {
  protected val TestDomain = "example.com"
  protected val TestXMPPSettings = XMPPSettings("localhost", 5222, TestDomain, "disabled", debug = false)

  protected val TestUsername = "foo"
  protected val TestPassword = "bigSecret"
  protected val TestNewPassword = "newPass"

  protected val TestContact1 = "friend1@domain.com"
  protected val TestContact2 = "friend2@domain.com"

  protected val TestChatMessage = "Chat message"
  protected val TestCallSignalData = """{"type":"offer","sdp":"v=0"}"""

  protected val TestDataObjectUri = new URI("http://tela/profileInfo")
  protected val TextHtmlContentType = "text/html"
  protected val WildcardSparqlQuery = "CONSTRUCT { ?s ?p ?o } WHERE {?s ?p ?o }"
}
