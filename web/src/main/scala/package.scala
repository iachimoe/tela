package tela

import java.util.concurrent.TimeUnit

import akka.util.Timeout
import io.netty.handler.codec.http.{CookieDecoder, HttpHeaders}
import org.mashupbots.socko.events.HttpRequestMessage

import scala.collection.JavaConversions._

package object web {
  private val DefaultEncoding = "UTF-8"
  private[web] val TextHtmlContentType: String = "text/html; charset=" + DefaultEncoding

  private[web] val SessionIdCookieName = "sessionId"
  private[web] val IndexPage: String = "index.html"
  private[web] val DefaultLanguage: String = "en"
  private[web] val LanguagesFolder: String = "languages"
  private[web] val LanguageFileExtension: String = ".json"

  def getSessionIdFromCookie(request: HttpRequestMessage): Option[String] = {
    request.headers.getAll(HttpHeaders.Names.COOKIE).flatMap(decodeCookie).find(_.getName == SessionIdCookieName).map(_.getValue)
  }

  def createTimeout: Timeout = {
    Timeout(3, TimeUnit.SECONDS)
  }

  def decodeCookie(cookie: String) = {
    asScalaSet(CookieDecoder.decode(cookie))
  }
}
