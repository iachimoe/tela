package tela.web

import javax.inject.{Inject, Named}

import akka.actor.ActorRef
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc._

import scala.concurrent.Future

class UserRequest[A](val sessionData: SessionData, request: Request[A]) extends WrappedRequest[A](request)

class UserAction @Inject()(@Named("session-manager") sessionManager: ActorRef) extends ActionBuilder[UserRequest] with ActionRefiner[Request, UserRequest] {
  def refine[A](request: Request[A]): Future[Either[Result, UserRequest[A]]] =
    getSessionFromRequest(request, sessionManager).map(_.map {
      case (sessionId, userData) => Right(new UserRequest(SessionData(sessionId, userData), request))
    }.getOrElse(Left(Results.Unauthorized)))
}
