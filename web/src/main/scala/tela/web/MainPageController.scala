package tela.web

import java.nio.file.{Path, Paths}
import java.util.UUID

import akka.actor.ActorRef
import akka.pattern.ask
import javax.inject.{Inject, Named}
import play.api.Logger
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json.{JsLookupResult, JsValue}
import play.api.mvc.{Request, _}
import tela.baseinterfaces.LoginFailure
import tela.web.MainPageController._
import tela.web.SessionManager.{Login, Logout}

import scala.concurrent.ExecutionContext

object MainPageController {
  private[web] val LoginPage: Path = Paths.get("login.html")
  private[web] val UsernameRequestParameter: String = "username"
  private[web] val PasswordRequestParameter: String = "password"
  private[web] val WebAppRoot: String = "/"
  private[web] val LogoutParameter: String = "logout"
  private[web] val CookieExpiresWhenBrowserCloses = None
  private[web] val CookieExpiresNow = Some(-1)

  private[web] val UserTemplateKey = "user"
  private[web] val ConnectionFailedError = "connectionFailed"
  private[web] val BadCredentialsError = "badCredentials"
  private[web] val LoginFailureReasons: Map[LoginFailure, String] = Map(LoginFailure.ConnectionFailure -> ConnectionFailedError, LoginFailure.InvalidCredentials -> BadCredentialsError)

  private[web] val AppInfoKey = "appInfo"
  private[web] val DefaultAppKey = "defaultApp"
  private[web] val LocalizedAppNamesKey = "localizedAppNames"

  private[web] val DefaultAppKeyInIndexHash = "defaultApp"
  private[web] val LanguagesKeyInIndexHash = "languages"
  private[web] val AppsKeyInIndexHash = "apps"

  case class LoginDetails(username: String, password: String)
}

class MainPageController @Inject()(
                                    @Named("session-manager") sessionManager: ActorRef,
                                    @Named("login-page-root") documentRoot: Path,
                                    @Named("app-index-file") appIndex: Path,
                                    controllerComponents: ControllerComponents
                                  )(implicit ec: ExecutionContext) extends AbstractController(controllerComponents) {
  private val appIndexData = JsonFileHelper.getContents(appIndex)

  private val loginDetailsForm = Form(mapping(
    UsernameRequestParameter -> text,
    PasswordRequestParameter -> text
  )(LoginDetails.apply)(LoginDetails.unapply))

  def mainPage(logout: Option[String] = None) = Action.async { request =>
    getSessionFromRequest(request, sessionManager).map {
      case Some((sessionId, userData)) =>
        if (logout.isEmpty) showMainPage(userData)
        else handleLogout(request, sessionId, userData)
      case None => showLoginPage(request)
    }
  }

  def handleLogin() = Action.async(parse.form(loginDetailsForm)) { request =>
    (sessionManager ? Login(request.body.username, request.body.password, getLanguageFromRequestHeader(request))).mapTo[Either[LoginFailure, UUID]].map {
      case Left(loginFailure) => showLoginPage(request, Map(UserTemplateKey -> request.body.username, LoginFailureReasons(loginFailure) -> true.toString))
      case Right(sessionId) => Redirect(WebAppRoot, Map(), status = FOUND).withCookies(createSessionCookie(sessionId, CookieExpiresWhenBrowserCloses))
    }
  }

  private def showMainPage(userData: UserData) = {
    Logger.info(s"Showing main page for user ${userData.username}")
    val preferredLanguage: JsLookupResult = appIndexData \ LanguagesKeyInIndexHash \ userData.preferredLanguage
    val languageToUse = preferredLanguage.toOption.map(_.toString()).getOrElse((appIndexData \ LanguagesKeyInIndexHash \ DefaultLanguage).as[JsValue].toString)
    Ok(getContent(documentRoot, IndexPage, userData.preferredLanguage, Map(UserTemplateKey -> userData.username,
      AppInfoKey -> (appIndexData \ AppsKeyInIndexHash).as[JsValue].toString(),
      DefaultAppKey -> (appIndexData \ DefaultAppKeyInIndexHash).as[String],
      LocalizedAppNamesKey -> languageToUse))).as(HTML)
  }

  private def handleLogout(request: Request[Any], sessionId: UUID, userData: UserData) = {
    Logger.info(s"User ${userData.username} logging out")
    sessionManager ! Logout(sessionId)
    showLoginPage(request, Map(UserTemplateKey -> userData.username)).withCookies(createSessionCookie(sessionId, MainPageController.CookieExpiresNow))
  }

  private def showLoginPage(request: Request[Any], templateMap: Map[String, String] = Map.empty): Result = {
    Logger.info(s"Showing login page")
    Ok(getContent(documentRoot, LoginPage, getLanguageFromRequestHeader(request), templateMap)).as(HTML)
  }

  private def createSessionCookie(sessionId: UUID, maxAge: Option[Int]) = Cookie(SessionIdCookieName, sessionId.toString, maxAge = maxAge)

  private def getLanguageFromRequestHeader(request: Request[Any]): String = {
    request.acceptLanguages.map(_.code.substring(0, 2)).headOption.getOrElse(DefaultLanguage)
  }
}
