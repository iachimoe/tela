package tela.web

import java.io.{File, FileReader, StringWriter}

import akka.actor.{Actor, ActorLogging, ActorRef}
import org.mashupbots.socko.events.{HttpRequestEvent, HttpRequestMessage, HttpResponseMessage, HttpResponseStatus}
import play.api.libs.json.Json
import tela.web.SessionManager.GetSession

import scala.collection.JavaConversions._
import scala.io.Source

abstract class RequestHandlerBase(protected val sessionManager: ActorRef) extends Actor with ActorLogging {
  protected def getDocumentRoot: String

  protected def sendByteArrayResponse(response: HttpResponseMessage, headers: Map[String, String], content: Array[Byte], status: HttpResponseStatus = HttpResponseStatus.OK): Unit = {
    response.status = status
    for ((name, value) <- headers) response.headers.put(name, value)
    response.write(content)
  }

  protected def sendResponse(response: HttpResponseMessage, headers: Map[String, String], content: String, status: HttpResponseStatus = HttpResponseStatus.OK): Unit = {
    sendByteArrayResponse(response, headers, content.getBytes(), status)
  }

  protected def performActionOnValidSessionOrSendUnauthorizedError(event: HttpRequestEvent, action: ((String, UserData) => Unit)): Unit = {
    performActionDependingOnWhetherSessionExists(event.request, action, () => sendResponseToUnauthorizedUser(event))
  }

  protected def performActionDependingOnWhetherSessionExists(request: HttpRequestMessage,
                                                             actionForExistingSession: ((String, UserData) => Unit),
                                                             actionForUnauthorizedUser: (() => (Unit))): Unit = {
    getSessionIdFromCookie(request) match {
      case Some(sessionId) =>
        sendMessageAndGetResponse[Option[UserData]](sessionManager, GetSession(sessionId)) match {
          case Some(userData) => actionForExistingSession(sessionId, userData)
          case None => actionForUnauthorizedUser()
        }
      case None => actionForUnauthorizedUser()
    }
  }

  private def sendResponseToUnauthorizedUser(event: HttpRequestEvent): Unit = {
    sendResponse(event.response, Map(), "", HttpResponseStatus.UNAUTHORIZED)
  }

  protected def displayPage(response: HttpResponseMessage, headers: Map[String, String], filename: String, preferredLanguage: String, templateMap: Map[String, String] = Map()): Unit = {
    response.contentType = TextHtmlContentType
    sendResponse(response, headers, getContent(filename, preferredLanguage, templateMap))
  }

  private def getContent(filename: String, preferredLanguage: String, templateMap: Map[String, String]): String = {
    val mustache = new NonEscapingMustacheFactory().compile(new FileReader(new File(getDocumentRoot, filename)), "")
    val writer = new StringWriter
    mustache.execute(writer, getTemplateMappings(preferredLanguage, templateMap).map(mapAsJavaMap).toArray[java.lang.Object])
    writer.toString
  }

  private def getTemplateMappings(preferredLanguage: String, templateMap: Map[String, String]): Seq[Map[String, String]] = {
    val languageFile = getFileForLanguage(preferredLanguage)
    if (preferredLanguage != DefaultLanguage && languageFile.exists)
      Seq(getMappingsForLanguage(getFileForLanguage(DefaultLanguage)), getMappingsForLanguage(languageFile), templateMap)
    else
      Seq(getMappingsForLanguage(getFileForLanguage(DefaultLanguage)), templateMap)
  }

  private def getMappingsForLanguage(mappingsFile: File): Map[String, String] = {
    Json.parse(Source.fromFile(mappingsFile).mkString).as[Map[String, String]]
  }

  private def getFileForLanguage(lang: String): File = {
    new File(getDocumentRoot + "/" + LanguagesFolder + "/" + lang + LanguageFileExtension)
  }
}
