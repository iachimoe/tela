package tela.web

import java.io.File

import akka.actor.ActorRef
import akka.pattern.ask
import org.mashupbots.socko.events.{HttpRequestEvent, HttpResponseStatus}
import tela.web.SessionManager.GetSession

import scala.concurrent.Await

class AppHandler(private val appsRootDirectory: String, private val appName: String, private val sessionManager: ActorRef) extends RequestHandlerBase {
  override def receive: Receive = {
    case event: HttpRequestEvent =>
      getSessionIdFromCookie(event.request) match {
        case Some(sessionId) =>
          implicit val timeout = ActorTimeout
          val future = sessionManager ? GetSession(sessionId)
          Await.result(future, timeout.duration).asInstanceOf[Option[UserData]] match {
            case Some(userData) =>
              log.info("User {} requesting app requesting app {}", userData.name, appName)
              handleAppRequest(event, userData.language)
            case None => sendResponseToUnauthorizedUser(event)
          }
        case None => sendResponseToUnauthorizedUser(event)
      }

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
