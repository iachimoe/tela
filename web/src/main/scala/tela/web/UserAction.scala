package tela.web

import akka.actor.ActorRef
import javax.inject.{Inject, Named}
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

class UserRequest[A](val sessionData: SessionData, request: Request[A]) extends WrappedRequest[A](request)

class UserAction @Inject()(@Named("session-manager") sessionManager: ActorRef)(implicit val ec: ExecutionContext, p: BodyParsers.Default) extends ActionBuilder[UserRequest, AnyContent] with ActionRefiner[Request, UserRequest] {
  def refine[A](request: Request[A]): Future[Either[Result, UserRequest[A]]] =
    getSessionFromRequest(request, sessionManager).map(_.map {
      case (sessionId, userData) => Right(new UserRequest(SessionData(sessionId, userData), request))
    }.getOrElse(Left(Results.Unauthorized)))

  override protected def executionContext: ExecutionContext = ec

  override def parser: BodyParser[AnyContent] = p
}
