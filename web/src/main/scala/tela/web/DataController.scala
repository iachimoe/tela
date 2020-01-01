package tela.web

import java.net.URI
import java.nio.file.{Path, Paths}

import akka.actor.ActorRef
import akka.pattern.ask
import javax.inject.{Inject, Named}
import play.api.Logging
import play.api.http.HttpEntity.Streamed
import play.api.libs.Files.TemporaryFile
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._
import tela.web.JSONConversions.TextSearchResult
import tela.web.SessionManager._

import scala.concurrent.ExecutionContext

class DataController @Inject()(
                                userAction: UserAction,
                                @Named("session-manager") sessionManager: ActorRef,
                                controllerComponents: ControllerComponents)(implicit ec: ExecutionContext) extends AbstractController(controllerComponents) with Logging {
  def publishData(uriToPublish: String): Action[JsValue] = userAction.apply(parse.tolerantJson) { implicit request =>
    logger.info(s"User ${request.sessionData.userData.username} publishing uri $uriToPublish")
    val uri = new URI(uriToPublish)
    sessionManager ! PublishData(request.sessionData.sessionId, request.body.toString(), uri)
    Ok
  }

  def uploadMediaItem(): Action[MultipartFormData[TemporaryFile]] = userAction.apply(parse.multipartFormData) { implicit request =>
    val files = request.body.files.head
    logger.info(s"User ${request.sessionData.userData.username} uploading file ${files.filename}")
    sessionManager ! StoreMediaItem(request.sessionData.sessionId, files.ref.path, Paths.get(files.filename))
    Ok
  }

  def retrieveData(uri: String, publisher: Option[String]) = userAction.async { implicit request =>
    val sessionId = request.sessionData.sessionId
    val uriObj = new URI(uri)
    val messageToSend = publisher.map(pub => RetrievePublishedData(sessionId, pub, uriObj)).getOrElse(RetrieveData(sessionId, uriObj))

    logger.info(s"User ${request.sessionData.userData.username} requesting uri $uri from publisher $publisher")
    (sessionManager ? messageToSend).mapTo[String].map(result => {
      Ok(result).as(JsonLdContentType)
    })
  }

  def downloadMediaItem(hash: String) = userAction.async { implicit request =>
    logger.info(s"User ${request.sessionData.userData.username} requesting media item $hash")
    (sessionManager ? RetrieveMediaItem(request.sessionData.sessionId, hash)).mapTo[Option[Path]].map {
      case Some(file) =>
        val r: Result = Ok.sendPath(file)
        //Play tends to set the content type to something very generic by default,
        //which prompts the browser to download (save to disk) rather than display the file in the browser
        //By not setting the content type, the browser will do its own checks to see whether it's something that it can render
        r.copy(body = r.body.asInstanceOf[Streamed].copy(contentType = None))
      case None => NotFound
    }
  }

  def sparqlQuery(query: String) = userAction.async { implicit request =>
    logger.info(s"User ${request.sessionData.userData.username} running SPARQL query $query")
    (sessionManager ? SPARQLQuery(request.sessionData.sessionId, query)).mapTo[String].map(result => {
      Ok(result).as(JsonLdContentType)
    })
  }

  def textSearch(text: String) = userAction.async { implicit request =>
    logger.info(s"User ${request.sessionData.userData.username} running text search for <$text>")
    (sessionManager ? TextSearch(request.sessionData.sessionId, text)).mapTo[TextSearchResult].map(result => {
      Ok(Json.toJson(result))
    })
  }
}
