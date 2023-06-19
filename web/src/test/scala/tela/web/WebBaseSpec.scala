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

  protected val BootstrapTestAssetPath = "/bootstrap.min.css"
  protected val FontAwesomeTestAssetPath = "/fa.min.css"
  protected val TextEditorTestAssetPath = "/textEditor.js"
}
