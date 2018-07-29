package tela

import java.io.StringWriter
import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import play.api.Logger
import play.api.mvc.RequestHeader
import tela.web.SessionManager.GetSession

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

package object web {
  private[web] val JsonLdContentType: String = "application/ld+json"

  private[web] val SessionIdCookieName = "sessionId"
  private[web] val IndexPage = Paths.get("index.html")
  private[web] val DefaultLanguage: String = "en"
  private[web] val LanguagesFolder = Paths.get("languages")
  private[web] val LanguageFileExtension: String = ".json"

  private val TimeoutDurationInSeconds = 9
  private[web] implicit val GeneralTimeout = Timeout(TimeoutDurationInSeconds, TimeUnit.SECONDS)

  def getSessionFromRequest(request: RequestHeader, sessionManager: ActorRef)(implicit ec: ExecutionContext): Future[Option[(UUID, UserData)]] = {
    request.cookies.get(SessionIdCookieName).map(cookie => {
      val sessionId = UUID.fromString(cookie.value)
      Logger.info(s"Request for session $sessionId")
      (sessionManager ? GetSession(sessionId)).mapTo[Option[UserData]].map(_.map(userData => sessionId -> userData))
    }).getOrElse(Future.successful(None))
  }

  def getContent(documentRoot: Path, filename: Path, preferredLanguage: String, templateMap: Map[String, String]): String = {
    val mustache = new NonEscapingMustacheFactory().compile(Files.newBufferedReader(documentRoot.resolve(filename)), "")
    val writer = new StringWriter
    mustache.execute(writer, getTemplateMappings(documentRoot, preferredLanguage, templateMap).map(_.asJava).toArray[java.lang.Object])
    writer.toString
  }

  private def getTemplateMappings(documentRoot: Path, preferredLanguage: String, templateMap: Map[String, String]): Vector[Map[String, String]] = {
    val languageFile = getFileForLanguage(documentRoot, preferredLanguage)
    if (preferredLanguage != DefaultLanguage && Files.exists(languageFile))
      Vector(getMappingsForLanguage(getFileForLanguage(documentRoot, DefaultLanguage)), getMappingsForLanguage(languageFile), templateMap)
    else
      Vector(getMappingsForLanguage(getFileForLanguage(documentRoot, DefaultLanguage)), templateMap)
  }

  private def getMappingsForLanguage(mappingsFile: Path): Map[String, String] = {
    JsonFileHelper.getContents(mappingsFile).as[Map[String, String]]
  }

  private def getFileForLanguage(documentRoot: Path, lang: String): Path = {
    documentRoot.resolve(LanguagesFolder).resolve(s"$lang$LanguageFileExtension")
  }
}
