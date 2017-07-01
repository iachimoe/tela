package tela.web

import tela.baseinterfaces.BaseSpec
import tela.web.JSONConversions.LanguageInfo

trait WebBaseSpec extends BaseSpec {
  protected val GeneralTimeoutAsDuration = GeneralTimeout.duration

  protected val TestSessionId = "aaaaaaaaa"

  protected val SpanishLanguageCode = "es"
  protected val UnknownLanguageCode = "XX"
  protected val TestLanguageInfo = LanguageInfo(Map(DefaultLanguage -> "English", SpanishLanguageCode -> "Español"), DefaultLanguage)
}
