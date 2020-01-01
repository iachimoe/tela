package tela.web

import akka.actor.ActorRef
import akka.pattern.ask
import javax.inject.{Inject, Named}
import play.api.Logging
import play.api.data.Form
import play.api.data.Forms.{mapping, _}
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, ControllerComponents}
import tela.web.JSONConversions.LanguageInfo
import tela.web.SessionManager.{ChangePassword, GetLanguages, SetLanguage}
import tela.web.SettingsController._

import scala.concurrent.ExecutionContext

object SettingsController {
  case class ChangePasswordRequest(oldPassword: String, newPassword: String)

  case class Language(code: String)
}

class SettingsController @Inject()(
                                    userAction: UserAction, @Named("session-manager") sessionManager: ActorRef, controllerComponents: ControllerComponents
                                  )(implicit ec: ExecutionContext) extends AbstractController(controllerComponents) with Logging {
  private val changePasswordForm = Form(mapping(JSONConversions.OldPasswordKey -> text,
    JSONConversions.NewPasswordKey -> text)(ChangePasswordRequest.apply)(ChangePasswordRequest.unapply))

  private val languageForm = Form(mapping(JSONConversions.LanguageKey -> text)(Language.apply)(Language.unapply))

  def changePassword() = userAction.async(parse.form(changePasswordForm)) { implicit request =>
    logger.info(s"User with session ID ${request.sessionData.sessionId} attempting to change password")

    val changePasswordRequest = request.body

    (sessionManager ? ChangePassword(request.sessionData.sessionId, changePasswordRequest.oldPassword, changePasswordRequest.newPassword)).mapTo[Boolean].map(result => {
      if (result) Ok else BadRequest
    })
  }

  def changeLanguage() = userAction.apply(parse.form(languageForm)) { implicit request =>
    logger.info(s"User with session ID ${request.sessionData.sessionId} changing language to ${request.body.code}")
    sessionManager ! SetLanguage(request.sessionData.sessionId, request.body.code)
    Ok
  }

  def listAvailableLanguages() = userAction.async { implicit request =>
    logger.info(s"User with session ID ${request.sessionData.sessionId} requesting available languages")
    (sessionManager ? GetLanguages(request.sessionData.sessionId)).mapTo[LanguageInfo].map(availableLanguages => {
      Ok(Json.toJson(availableLanguages))
    })
  }
}
