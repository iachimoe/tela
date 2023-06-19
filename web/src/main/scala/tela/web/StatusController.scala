package tela.web

import play.api.Logging
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

import javax.inject.Inject

class StatusController @Inject()(controllerComponents: ControllerComponents)
  extends AbstractController(controllerComponents) with Logging {
  def ping(): Action[AnyContent] = Action {
    logger.debug("Received ping request")
    Ok("OK")
  }
}
