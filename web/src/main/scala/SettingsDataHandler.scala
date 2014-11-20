package tela.web

import akka.actor.ActorRef
import org.mashupbots.socko.events.{HttpRequestEvent, HttpResponseStatus}
import play.api.libs.json.Json
import tela.web.JSONConversions.LanguageInfo
import tela.web.SessionManager._
import tela.web.SettingsDataHandler._

object SettingsDataHandler {
  private[web] val Password = "password"
  private[web] val Language = "language"
  private[web] val Languages = "languages"
}

class SettingsDataHandler(sessionManager: ActorRef, setting: String) extends RequestHandlerBase(sessionManager) {
  override def receive: Receive = {
    case event: HttpRequestEvent =>
      performActionOnValidSessionOrSendUnauthorizedError(event, (sessionId: String, userData: UserData) => {
        setting match {
          case Password => handlePasswordChangeRequest(event, sessionId)
          case Language => handleSetLanguageRequest(event, sessionId)
          case Languages => handleGetLanguagesRequest(event, sessionId)
        }
      })

      context.stop(self)
  }

  private def handleSetLanguageRequest(event: HttpRequestEvent, sessionId: String): Unit = {
    sessionManager ! SetLanguage(sessionId, event.request.content.toFormDataMap(JSONConversions.LanguageKey)(0))
    sendResponse(event.response, Map(), "")
  }

  private def handleGetLanguagesRequest(event: HttpRequestEvent, sessionId: String): Unit = {
    val result = sendMessageAndGetResponse[LanguageInfo](sessionManager, GetLanguages(sessionId))
    event.response.contentType = JsonContentType
    sendResponse(event.response, Map(), Json.stringify(Json.toJson(result)))
  }

  private def handlePasswordChangeRequest(event: HttpRequestEvent, sessionId: String): Unit = {
    val formDataMap = event.request.content.toFormDataMap
    val oldPassword: String = formDataMap(JSONConversions.OldPasswordKey)(0)
    val newPassword: String = formDataMap(JSONConversions.NewPasswordKey)(0)
    log.info("User with session ID attempting to change password {}", sessionId)
    val result = sendMessageAndGetResponse[Boolean](sessionManager, ChangePassword(sessionId, oldPassword, newPassword))
    sendResponse(event.response, Map(), "", if (result) HttpResponseStatus.OK else HttpResponseStatus.PRECONDITION_FAILED)
  }

  override protected def getDocumentRoot: String = ???
}
