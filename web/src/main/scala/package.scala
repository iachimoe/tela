package tela

import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import io.netty.handler.codec.http.{CookieDecoder, HttpHeaders}
import org.mashupbots.socko.events.HttpRequestMessage

import scala.collection.JavaConversions._
import scala.concurrent.Await

package object web {
  private[web] val DefaultEncoding = "UTF-8"
  private[web] val TextHtmlContentType: String = "text/html; charset=" + DefaultEncoding
  private[web] val JsonLdContentType: String = "application/ld+json"
  private[web] val JsonContentType: String = "application/json"

  private[web] val SessionIdCookieName = "sessionId"
  private[web] val IndexPage: String = "index.html"
  private[web] val DefaultLanguage: String = "en"
  private[web] val LanguagesFolder: String = "languages"
  private[web] val LanguageFileExtension: String = ".json"

  private val TimeoutDurationInSeconds = 9

  def sendMessageAndGetResponse[ResponseType](actor: ActorRef, message: Any): ResponseType = {
    implicit val timeout = Timeout(TimeoutDurationInSeconds, TimeUnit.SECONDS)
    Await.result(actor ? message, timeout.duration).asInstanceOf[ResponseType]
  }

  def getSessionIdFromCookie(request: HttpRequestMessage): Option[String] = {
    request.headers.getAll(HttpHeaders.Names.COOKIE).flatMap(decodeCookie).find(_.getName == SessionIdCookieName).map(_.getValue)
  }

  def decodeCookie(cookie: String) = {
    asScalaSet(CookieDecoder.decode(cookie))
  }
}