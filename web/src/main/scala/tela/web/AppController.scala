package tela.web

import java.nio.file.{Files, Path}
import controllers.AssetsFinder

import javax.inject.{Inject, Named}
import play.api.Logging
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

class AppController @Inject()(userAction: UserAction,
                              @Named("apps-root-directory") appsRootDirectory: Path,
                              controllerComponents: ControllerComponents,
                              assetsFinder: AssetsFinder
                             ) extends AbstractController(controllerComponents) with Logging {
  def app(name: String): Action[AnyContent] = userAction { request: UserRequest[AnyContent] =>
    logger.info(s"User ${request.sessionData.userData.username} requesting app $name")
    val appDir = appsRootDirectory.resolve(name)
    val mainFile = appDir.resolve(IndexPage)

    if (Files.exists(mainFile))
      Ok(getContent(appDir, IndexPage, request.sessionData.userData.preferredLanguage, Map(), assetsFinder)).as(HTML)
    else
      NotFound
  }
}
