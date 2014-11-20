package tela.web

import java.nio.charset.Charset

import akka.actor.ActorRef
import org.mashupbots.socko.events.{HttpRequestEvent, HttpResponseMessage, HttpResponseStatus}
import tela.web.DataHandler._
import tela.web.SessionManager.{PublishData, RetrieveData, RetrievePublishedData}

object DataHandler {
  private[web] val DataUriParameter = "uri"
  private[web] val PublisherParameter = "publisher"
  private[web] val PublishUriParameter = "uriToPublish"
}

class DataHandler(sessionManager: ActorRef) extends RequestHandlerBase(sessionManager) {
  override def receive: Receive = {
    case event: HttpRequestEvent =>
      performActionOnValidSessionOrSendUnauthorizedError(event, (sessionId: String, userData: UserData) => {
        if (event.endPoint.isGET) {
          log.info("User {} requesting data", userData.name)
          if (event.endPoint.queryStringMap.contains(DataUriParameter)) {
            val uri: String = event.endPoint.queryStringMap(DataUriParameter)(0)

            if (event.endPoint.queryStringMap.contains(PublisherParameter)) {
              val publisher = event.endPoint.queryStringMap(PublisherParameter)(0)
              log.info("User {} requesting uri {} from publisher {}", userData.name, uri, publisher)
              sendDataFromSessionManagerToClient(RetrievePublishedData(sessionId, publisher, uri), event.response)
            }
            else {
              log.info("User {} requesting uri {}", userData.name, uri)
              sendDataFromSessionManagerToClient(RetrieveData(sessionId, uri), event.response)
            }
          }
          else {
            log.info("Request for data without URI from {}", userData.name)
            sendResponse(event.response, Map(), "", HttpResponseStatus.BAD_REQUEST)
          }
        }
        else if (event.endPoint.isPUT) {
          val uri: String = event.endPoint.queryStringMap(PublishUriParameter)(0)
          log.info("User {} uploading data and publishing at {}", userData.name, uri)
          sessionManager ! PublishData(sessionId,
            event.request.content.toString(Charset.forName(DefaultEncoding)),
            uri)
          sendResponse(event.response, Map(), "")
        }
      })

      context.stop(self)
  }

  private def sendDataFromSessionManagerToClient(messageToSessionManager: Any, response: HttpResponseMessage): Unit = {
    val result = sendMessageAndGetResponse[String](sessionManager, messageToSessionManager)
    response.contentType = JsonLdContentType
    sendResponse(response, Map(), result)
  }

  override protected def getDocumentRoot: String = ???
}
