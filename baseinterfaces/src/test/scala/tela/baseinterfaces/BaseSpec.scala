package tela.baseinterfaces

import java.net.URI
import org.scalactic.TypeCheckedTripleEquals
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatestplus.mockito.MockitoSugar

import java.time.LocalDateTime

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
  protected val WildcardSparqlQuery = "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }"

  protected val TestDateInHttpHeaderFormat = "Tue, 05 Apr 2016 15:15:49 GMT"
  protected val TestDateAsLocalDateTime = LocalDateTime.of(2016, 4, 5, 15, 15, 49)
}
