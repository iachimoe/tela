package tela.web

import java.io.File
import javax.inject.{Inject, Named}

import akka.actor.ActorRef
import akka.pattern.ask
import play.api.Logger
import play.api.http.HttpEntity.Streamed
import play.api.libs.Files.TemporaryFile
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Action, Controller, MultipartFormData, Result}
import tela.web.JSONConversions.TextSearchResult
import tela.web.SessionManager._

class DataController @Inject()(userAction: UserAction, @Named("session-manager") sessionManager: ActorRef) extends Controller {
  def publishData(uriToPublish: String): Action[JsValue] = userAction.apply(parse.tolerantJson) { implicit request =>
    Logger.info(s"User ${request.sessionData.userData.username} publishing uri $uriToPublish")
    sessionManager ! PublishData(request.sessionData.sessionId, request.body.toString(), uriToPublish)
    Ok
  }

  def uploadMediaItem(): Action[MultipartFormData[TemporaryFile]] = userAction.apply(parse.multipartFormData) { implicit request =>
    val files = request.body.files.head
    Logger.info(s"User ${request.sessionData.userData.username} uploading file ${files.filename}")
    val ref: TemporaryFile = files.ref
    sessionManager ! StoreMediaItem(request.sessionData.sessionId, ref.file.getAbsolutePath, files.filename)
    Ok
  }

  def retrieveData(uri: String, publisher: Option[String]) = userAction.async { implicit request =>
    val sessionId = request.sessionData.sessionId
    val messageToSend = publisher.map(pub => RetrievePublishedData(sessionId, pub, uri)).getOrElse(RetrieveData(sessionId, uri))

    Logger.info(s"User ${request.sessionData.userData.username} requesting uri $uri from publisher $publisher")
    (sessionManager ? messageToSend).mapTo[String].map(result => {
      Ok(result).as(JsonLdContentType)
    })
  }

  def downloadMediaItem(hash: String) = userAction.async { implicit request =>
    Logger.info(s"User ${request.sessionData.userData.username} requesting media item $hash")
    (sessionManager ? RetrieveMediaItem(request.sessionData.sessionId, hash)).mapTo[Option[String]].map {
      case Some(fileName) =>
        val r: Result = Ok.sendFile(new File(fileName))
        //Play tends to set the content type to something very generic by default,
        //which prompts the browser to download (save to disk) rather than display the file in the browser
        //By not setting the content type, the browser will do its own checks to see whether it's something that it can render
        r.copy(body = r.body.asInstanceOf[Streamed].copy(contentType = None))
      case None => NotFound
    }
  }

  def sparqlQuery(query: String) = userAction.async { implicit request =>
    Logger.info(s"User ${request.sessionData.userData.username} running SPARQL query $query")
    (sessionManager ? SPARQLQuery(request.sessionData.sessionId, query)).mapTo[String].map(result => {
      Ok(result).as(JsonLdContentType)
    })
  }

  def textSearch(text: String) = userAction.async { implicit request =>
    Logger.info(s"User ${request.sessionData.userData.username} running text search for <$text>")
    (sessionManager ? TextSearch(request.sessionData.sessionId, text)).mapTo[TextSearchResult].map(result => {
      Ok(Json.toJson(result))
    })
  }
}
