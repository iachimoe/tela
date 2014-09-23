package tela.web

import java.io.{File, FileReader, StringWriter}

import akka.actor.{ActorLogging, Actor}
import com.github.mustachejava.DefaultMustacheFactory
import org.mashupbots.socko.events.{HttpResponseMessage, HttpResponseStatus}
import play.api.libs.json.Json

import scala.collection.JavaConversions._
import scala.io.Source

abstract class RequestHandlerBase extends Actor with ActorLogging {
  protected def getDocumentRoot: String

  protected def sendResponse(response: HttpResponseMessage, headers: Map[String, String], content: String, status: HttpResponseStatus = HttpResponseStatus.OK): Unit = {
    response.status = status
    for ((name, value) <- headers) response.headers.put(name, value)
    response.write(content)
  }

  protected def displayPage(response: HttpResponseMessage, headers: Map[String, String], filename: String, preferredLanguage: String, templateMap: Map[String, String] = Map()): Unit = {
    response.contentType = TextHtmlContentType
    sendResponse(response, headers, getContent(filename, preferredLanguage, templateMap))
  }

  private def getContent(filename: String, preferredLanguage: String, templateMap: Map[String, String]): String = {
    val mustache = new DefaultMustacheFactory().compile(new FileReader(new File(getDocumentRoot, filename)), "")
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
