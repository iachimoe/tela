package tela.web

import java.util.UUID

import tela.baseinterfaces.BaseSpec
import tela.web.JSONConversions.LanguageInfo

trait WebBaseSpec extends BaseSpec {
  protected val GeneralTimeoutAsDuration = GeneralTimeout.duration

  protected val TestSessionIdAsString = "00000000-0000-0000-c000-000000000046"
  protected val TestSessionId = UUID.fromString(TestSessionIdAsString)

  protected val SpanishLanguageCode = "es"
  protected val UnknownLanguageCode = "XX"
  protected val TestLanguageInfo = LanguageInfo(Map(DefaultLanguage -> "English", SpanishLanguageCode -> "Español"), DefaultLanguage)

  // Note that these paths have /webjars in front of them in production, due to how the routes are set up
  protected val BootstrapWebjarPath = "/bootstrap/5.1.3/dist/css/bootstrap.min.css"
  protected val FontAwesomeWebjarPath = "/font-awesome/6.1.0/css/all.min.css"
}
