package tela.web

import java.util.UUID

case class UserData(username: String, preferredLanguage: String)

case class SessionData(sessionId: UUID, userData: UserData)
