package tela.web

import java.io.File

import akka.actor.ActorRef
import org.mashupbots.socko.events.{HttpRequestEvent, HttpResponseStatus}

class AppHandler(sessionManager: ActorRef, appsRootDirectory: String, appName: String) extends RequestHandlerBase(sessionManager) {
  override def receive: Receive = {
    case event: HttpRequestEvent =>
      performActionOnValidSessionOrSendUnauthorizedError(event, (sessionId: String, userData: UserData) => {
        log.info("User {} requesting app requesting app {}", userData.name, appName)
        handleAppRequest(event, userData.language)
      })
      context.stop(self)
  }

  private def handleAppRequest(event: HttpRequestEvent, language: String): Unit = {
    val appDir = new File(appsRootDirectory, appName)
    val mainFile = new File(appDir, IndexPage)

    if (mainFile.exists)
      displayPage(event.response, Map(), IndexPage, language)
    else
      sendResponse(event.response, Map(), "", HttpResponseStatus.NOT_FOUND)
  }

  override protected def getDocumentRoot: String = {
    appsRootDirectory + "/" + appName
  }
}
