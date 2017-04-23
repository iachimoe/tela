package tela.web

case class UserData(username: String, preferredLanguage: String)

case class SessionData(sessionId: String, userData: UserData)
