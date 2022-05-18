package tela.web

import java.nio.file.{Files, Path}
import akka.actor.ActorRef
import org.webjars.play.WebJarsUtil

import javax.inject.{Inject, Named}
import play.api.Logging
import play.api.mvc.{AbstractController, AnyContent, ControllerComponents}

class AppController @Inject()(
                               userAction: UserAction,
                               @Named("session-manager") sessionManager: ActorRef,
                               @Named("apps-root-directory") appsRootDirectory: Path,
                               controllerComponents: ControllerComponents,
                               webjarsUtil: WebJarsUtil
                             ) extends AbstractController(controllerComponents) with Logging {
  def app(name: String) = userAction { request: UserRequest[AnyContent] =>
    logger.info(s"User ${request.sessionData.userData.username} requesting app $name")
    val appDir = appsRootDirectory.resolve(name)
    val mainFile = appDir.resolve(IndexPage)

    if (Files.exists(mainFile))
      Ok(getContent(appDir, IndexPage, request.sessionData.userData.preferredLanguage, Map(), webjarsUtil)).as(HTML)
    else
      NotFound
  }
}
