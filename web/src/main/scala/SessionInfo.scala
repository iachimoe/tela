package tela.web

import tela.baseinterfaces.XMPPSession

class SessionInfo(val xmppSession: XMPPSession, val userData: UserData, val webSockets: Set[String] = Set()) {
  def changeLanguage(language: String): SessionInfo = {
    new SessionInfo(xmppSession, new UserData(userData.name, language), webSockets)
  }

  def addWebSocket(webSocket: String): SessionInfo = {
    new SessionInfo(xmppSession, userData, webSockets + webSocket)
  }

  def removeWebSocket(webSocket: String): SessionInfo = {
    new SessionInfo(xmppSession, userData, webSockets - webSocket)
  }
}
