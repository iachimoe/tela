package tela.web

import java.io.{File, FileOutputStream}
import java.nio.charset.Charset
import java.nio.file.{Files, Paths}

import akka.actor.ActorRef
import org.mashupbots.socko.events.{HttpRequestEvent, HttpResponseMessage, HttpResponseStatus}
import play.api.libs.json.Json
import tela.web.DataHandler._
import tela.web.JSONConversions.TextSearchResult
import tela.web.SessionManager._

object DataHandler {
  private[web] val DataUriParameter = "uri"
  private[web] val SPARQLQueryParameter = "query"
  private[web] val PublisherParameter = "publisher"
  private[web] val PublishUriParameter = "uriToPublish"
  private[web] val MediaItemUriParameter = "hash"
  private[web] val TextQueryParameter = "text"

  //TODO I don't like using a custom header to specify the filename - find a better way
  private[web] val FilenameHTTPHeader = "X-Filename"
}

class DataHandler(sessionManager: ActorRef) extends RequestHandlerBase(sessionManager) {
  override def receive: Receive = {
    case event: HttpRequestEvent =>
      performActionOnValidSessionOrSendUnauthorizedError(event, (sessionId: String, userData: UserData) => {
        if (event.endPoint.isGET) {
          log.info("User {} requesting data", userData.name)
          if (event.endPoint.queryStringMap.contains(DataUriParameter)) {
            val uri: String = event.endPoint.queryStringMap(DataUriParameter).head

            if (event.endPoint.queryStringMap.contains(PublisherParameter)) {
              val publisher = event.endPoint.queryStringMap(PublisherParameter).head
              log.info("User {} requesting uri {} from publisher {}", userData.name, uri, publisher)
              sendDataFromSessionManagerToClient(RetrievePublishedData(sessionId, publisher, uri), event.response)
            }
            else {
              log.info("User {} requesting uri {}", userData.name, uri)
              sendDataFromSessionManagerToClient(RetrieveData(sessionId, uri), event.response)
            }
          } else if (event.endPoint.queryStringMap.contains(MediaItemUriParameter)) {
            retrieveMediaItem(event, sessionId, userData)
          } else if (event.endPoint.queryStringMap.contains(SPARQLQueryParameter)) {
            val query = event.endPoint.queryStringMap(SPARQLQueryParameter).head
            log.info("User {} running SPARQL query {}", userData.name, query)
            sendDataFromSessionManagerToClient(SPARQLQuery(sessionId, query), event.response)
          } else if (event.endPoint.queryStringMap.contains(TextQueryParameter)) {
            val query = event.endPoint.queryStringMap(TextQueryParameter).head
            val result = sendMessageAndGetResponse[TextSearchResult](sessionManager, TextSearch(sessionId, query))
            event.response.contentType = JsonContentType
            sendResponse(event.response, Map(), Json.toJson(result).toString())
          }
          else {
            log.info("Request for data without URI from {}", userData.name)
            sendResponse(event.response, Map(), "", HttpResponseStatus.BAD_REQUEST)
          }
        }
        else if (event.endPoint.isPUT) {
          if (event.request.contentType == JsonLdContentType) {
            storeAndPublishData(event, sessionId, userData)
          }
          else {
            storeMediaItem(event, sessionId)
          }
          sendResponse(event.response, Map(), "")
        }
      })

      context.stop(self)
  }

  private def retrieveMediaItem(event: HttpRequestEvent, sessionId: String, userData: UserData): Unit = {
    val hash = event.endPoint.queryStringMap(MediaItemUriParameter).head
    log.info("User {} requesting data with hash {}", userData.name, hash)

    sendMessageAndGetResponse[Option[String]](sessionManager, RetrieveMediaItem(sessionId, hash)) match {
      case Some(fileToSend) =>
        log.info("Returning file for user {} with hash {}", userData.name, hash)
        sendByteArrayResponse(event.response, Map(), Files.readAllBytes(Paths.get(fileToSend)))
      case None =>
        log.info("Could not find file for user {} with hash {}", userData.name, hash)
        sendResponse(event.response, Map(), "", HttpResponseStatus.NOT_FOUND)
    }
  }

  private def storeAndPublishData(event: HttpRequestEvent, sessionId: String, userData: UserData): Unit = {
    val uri: String = event.endPoint.queryStringMap(PublishUriParameter).head
    log.info("User {} uploading data and publishing at {}", userData.name, uri)
    sessionManager ! PublishData(sessionId,
      event.request.content.toString(Charset.forName(DefaultEncoding)),
      uri)
  }

  private def storeMediaItem(event: HttpRequestEvent, sessionId: String): Unit = {
    val tempFile = File.createTempFile("tela", null)
    val stream = new FileOutputStream(tempFile)
    try {
      stream.write(event.request.content.toBytes)
    } finally {
      stream.close()
    }
    sessionManager ! StoreMediaItem(sessionId, tempFile.getAbsolutePath, event.request.headers.get(FilenameHTTPHeader))
  }

  private def sendDataFromSessionManagerToClient(messageToSessionManager: Any, response: HttpResponseMessage): Unit = {
    val result = sendMessageAndGetResponse[String](sessionManager, messageToSessionManager)
    response.contentType = JsonLdContentType
    sendResponse(response, Map(), result)
  }

  override protected def getDocumentRoot: String = ???
}
