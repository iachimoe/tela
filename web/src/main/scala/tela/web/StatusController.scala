package tela.web

import play.api.Logging
import play.api.mvc.{AbstractController, ControllerComponents}

import javax.inject.Inject
import scala.concurrent.ExecutionContext

class StatusController @Inject()(controllerComponents: ControllerComponents)(implicit ec: ExecutionContext)
  extends AbstractController(controllerComponents) with Logging {
  def ping() = Action {
    logger.debug("Received ping request")
    Ok("OK")
  }
}
