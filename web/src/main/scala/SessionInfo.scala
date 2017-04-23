package tela.web

import akka.actor.ActorRef
import tela.baseinterfaces.{DataStoreConnection, XMPPSession}

class SessionInfo(val xmppSession: XMPPSession, val dataStoreConnection: DataStoreConnection, val userData: UserData, val webSockets: Set[ActorRef] = Set()) {
  def changeLanguage(language: String): SessionInfo = {
    new SessionInfo(xmppSession, dataStoreConnection, UserData(userData.username, language), webSockets)
  }

  def addWebSocket(webSocket: ActorRef): SessionInfo = {
    new SessionInfo(xmppSession, dataStoreConnection, userData, webSockets + webSocket)
  }

  def removeWebSocket(webSocket: ActorRef): SessionInfo = {
    new SessionInfo(xmppSession, dataStoreConnection, userData, webSockets - webSocket)
  }
}
