package tela.web

import java.io.File
import javax.inject.{Inject, Named}

import akka.actor.ActorRef
import play.api.Logger
import play.api.mvc.{AnyContent, Controller}

class AppController @Inject()(userAction: UserAction, @Named("session-manager") sessionManager: ActorRef, @Named("apps-root-directory") appsRootDirectory: String) extends Controller {
  def app(name: String) = userAction.apply { (request: UserRequest[AnyContent]) =>
    Logger.info(s"User ${request.sessionData.userData.username} requesting media app $name")
    val appDir = new File(appsRootDirectory, name)
    val mainFile = new File(appDir, IndexPage)

    if (mainFile.exists)
      Ok(getContent(appDir.getAbsolutePath, IndexPage, request.sessionData.userData.preferredLanguage, Map())).as(HTML)
    else
      NotFound
  }
}
