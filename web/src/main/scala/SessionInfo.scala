package tela.web

import tela.baseinterfaces.{DataStoreConnection, XMPPSession}

class SessionInfo(val xmppSession: XMPPSession, val dataStoreConnection: DataStoreConnection, val userData: UserData, val webSockets: Set[String] = Set()) {
  def changeLanguage(language: String): SessionInfo = {
    new SessionInfo(xmppSession, dataStoreConnection, new UserData(userData.name, language), webSockets)
  }

  def addWebSocket(webSocket: String): SessionInfo = {
    new SessionInfo(xmppSession, dataStoreConnection, userData, webSockets + webSocket)
  }

  def removeWebSocket(webSocket: String): SessionInfo = {
    new SessionInfo(xmppSession, dataStoreConnection, userData, webSockets - webSocket)
  }
}
