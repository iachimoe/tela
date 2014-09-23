package tela.web

import akka.actor.ActorRef
import akka.pattern.ask
import io.netty.handler.codec.http._
import org.mashupbots.socko.events.{HttpRequestEvent, HttpRequestMessage, HttpResponseStatus}
import tela.baseinterfaces.LoginFailure
import tela.web.MainPageHandler._
import tela.web.SessionManager.{GetSession, Login, Logout}

import scala.concurrent.Await

object MainPageHandler {
  private[web] val LoginPage: String = "login.html"
  private[web] val UsernameRequestParameter: String = "username"
  private[web] val PasswordRequestParameter: String = "password"
  private[web] val WebAppRoot: String = "/"
  private[web] val LogoutParameter: String = "logout"
  private[web] val CookieExpiresWhenBrowserCloses = java.lang.Long.MIN_VALUE
  private[web] val CookieExpiresNow = 0

  private[web] val UserTemplateKey = "user"
  private[web] val ConnectionFailedError = "connectionFailed"
  private[web] val BadCredentialsError = "badCredentials"
  private[web] val LoginFailureReasons: Map[LoginFailure, String] = Map(LoginFailure.ConnectionFailure -> ConnectionFailedError, LoginFailure.InvalidCredentials -> BadCredentialsError)
}

class MainPageHandler(private val documentRoot: String, private val sessionManager: ActorRef) extends RequestHandlerBase {
  override def receive: Receive = {
    case event: HttpRequestEvent =>
      if (event.endPoint.isGET) {
        getUserFromCookie(event.request) match {
          case Some((sessionId, userData)) =>
            if (event.endPoint.queryStringMap.contains(LogoutParameter))
              handleLogout(event, sessionId, userData)
            else
              displayMainPage(event, userData)
          case None => displayLoginScreen(event)
        }
      }
      else if (event.endPoint.isPOST)
        handleLoginAttempt(event)

      context.stop(self)
  }

  override protected def getDocumentRoot: String = {
    documentRoot
  }

  private def handleLoginAttempt(event: HttpRequestEvent): Unit = {
    val formDataMap = event.request.content.toFormDataMap
    val username: String = formDataMap(UsernameRequestParameter)(0)
    log.info("Login attempt by user {}", username)

    attemptLogin(username, formDataMap(PasswordRequestParameter)(0), getLanguageFromRequestHeader(event.request)) match {
      case Left(failureReason) => displayLoginScreenAfterFailedLogin(event, username, failureReason)
      case Right(sessionId) => handleSuccessfulLogin(event, sessionId)
    }
  }

  private def displayLoginScreenAfterFailedLogin(event: HttpRequestEvent, username: String, failureReason: LoginFailure): Unit = {
    log.info("Login failed for user {}", username)
    displayPage(event.response, Map(), LoginPage,
      getLanguageFromRequestHeader(event.request),
      Map(UserTemplateKey -> username, LoginFailureReasons(failureReason) -> true.toString))
  }

  private def displayLoginScreen(event: HttpRequestEvent): Unit =  {
    displayPage(event.response, Map(), LoginPage, getLanguageFromRequestHeader(event.request))
  }

  private def displayMainPage(event: HttpRequestEvent, userData: UserData): Unit = {
    displayPage(event.response, Map(), IndexPage, userData.language, Map(UserTemplateKey -> userData.name))
  }

  private def handleSuccessfulLogin(event: HttpRequestEvent, sessionId: String): Unit = {
    log.info("Login succeeded, sessionId is {}", sessionId)
    sendResponse(event.response,
      Map(HttpHeaders.Names.SET_COOKIE -> createCookieString(SessionIdCookieName, sessionId, MainPageHandler.CookieExpiresWhenBrowserCloses),
        HttpHeaders.Names.LOCATION -> MainPageHandler.WebAppRoot),
      "", HttpResponseStatus.FOUND)
  }

  private def handleLogout(event: HttpRequestEvent, sessionId: String, userData: UserData): Unit = {
    log.info("Logout out user {} with sessionId {}", userData.name, sessionId)
    sessionManager ! Logout(sessionId)
    displayPage(event.response, Map(HttpHeaders.Names.SET_COOKIE -> createCookieString(SessionIdCookieName, sessionId, MainPageHandler.CookieExpiresNow)),
      LoginPage, getLanguageFromRequestHeader(event.request), Map(UserTemplateKey -> userData.name))
  }

  private def getLanguageFromRequestHeader(request: HttpRequestMessage): String = {
    request.headers.getAll(HttpHeaders.Names.ACCEPT_LANGUAGE).map(_.substring(0, 2)).find(_ => true).getOrElse(DefaultLanguage)
  }

  private def attemptLogin(username: String, password: String, preferredLanguage: String): Either[LoginFailure, String] = {
    implicit val timeout = createTimeout
    val future = sessionManager ? Login(username, password, preferredLanguage)
    Await.result(future, timeout.duration).asInstanceOf[Either[LoginFailure, String]]
  }

  private def createCookieString(name: String, value: String, maxAge: Long): String = {
    val cookie = new DefaultCookie(name, value)
    cookie.setMaxAge(maxAge)
    ServerCookieEncoder.encode(cookie)
  }

  private def getUserFromCookie(request: HttpRequestMessage): Option[(String, UserData)] = {
    getSessionIdFromCookie(request).flatMap(getUserFromCookie)
  }

  private def getUserFromCookie(sessionId: String): Option[(String, UserData)] = {
    implicit val timeout = createTimeout
    val future = sessionManager ? GetSession(sessionId)
    val username = Await.result(future, timeout.duration).asInstanceOf[Option[UserData]]
    username.map((sessionId, _))
  }
}
