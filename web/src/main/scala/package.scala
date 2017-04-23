package tela

import java.io.{File, FileReader, StringWriter}
import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import akka.util.Timeout
import akka.pattern.ask
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.RequestHeader
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import tela.web.SessionManager.GetSession

import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.io.Source

package object web {
  private[web] val JsonLdContentType: String = "application/ld+json"

  private[web] val SessionIdCookieName = "sessionId"
  private[web] val IndexPage: String = "index.html"
  private[web] val DefaultLanguage: String = "en"
  private[web] val LanguagesFolder: String = "languages"
  private[web] val LanguageFileExtension: String = ".json"

  private[web] val TimeoutDurationInSeconds = 9
  private[web] implicit val timeout = Timeout(TimeoutDurationInSeconds, TimeUnit.SECONDS)

  def getSessionFromRequest(request: RequestHeader, sessionManager: ActorRef): Future[Option[(String, UserData)]] = {
    request.cookies.get(SessionIdCookieName).map(cookie => {
      val sessionId = cookie.value
      Logger.info(s"Request for session $sessionId")
      (sessionManager ? GetSession(sessionId)).mapTo[Option[UserData]].map(_.map(userData => sessionId -> userData))
    }).getOrElse(Future.successful(None))
  }

  def getContent(documentRoot: String, filename: String, preferredLanguage: String, templateMap: Map[String, String]): String = {
    val mustache = new NonEscapingMustacheFactory().compile(new FileReader(new File(documentRoot, filename)), "")
    val writer = new StringWriter
    mustache.execute(writer, getTemplateMappings(documentRoot, preferredLanguage, templateMap).map(mapAsJavaMap).toArray[java.lang.Object])
    writer.toString
  }

  private def getTemplateMappings(documentRoot: String, preferredLanguage: String, templateMap: Map[String, String]): Seq[Map[String, String]] = {
    val languageFile = getFileForLanguage(documentRoot, preferredLanguage)
    if (preferredLanguage != DefaultLanguage && languageFile.exists)
      Seq(getMappingsForLanguage(getFileForLanguage(documentRoot, DefaultLanguage)), getMappingsForLanguage(languageFile), templateMap)
    else
      Seq(getMappingsForLanguage(getFileForLanguage(documentRoot, DefaultLanguage)), templateMap)
  }

  private def getMappingsForLanguage(mappingsFile: File): Map[String, String] = {
    Json.parse(Source.fromFile(mappingsFile).mkString).as[Map[String, String]]
  }

  private def getFileForLanguage(documentRoot: String, lang: String): File = {
    new File(documentRoot + "/" + LanguagesFolder + "/" + lang + LanguageFileExtension)
  }
}
