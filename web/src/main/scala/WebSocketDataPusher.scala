package tela.web

import akka.actor.Actor
import play.api.libs.json.Json
import tela.web.JSONConversions._
import tela.web.WebSocketDataPusher._

object WebSocketDataPusher
{
  case class CloseSockets(ids: Set[String])

  case class SendLanguages(languages: LanguageInfo, ids: Set[String])

  case class SendChangePasswordResult(result: Boolean, ids: Set[String])

  case class SendContactListInfo(contacts: AddContacts, ids: Set[String])

  case class SendPresenceUpdate(update: PresenceUpdate, ids: Set[String])
}

class WebSocketDataPusher(sendInformationToWebSockets: (String, Iterable[String]) => Unit,
                          closeWebSockets: (Iterable[String]) =>  Unit) extends Actor {
  override def receive: Receive = {
    case CloseSockets(ids) => closeWebSockets(ids)
    case SendLanguages(languages, ids) => sendInformationToWebSockets(Json.stringify(Json.toJson(languages)), ids)
    case SendChangePasswordResult(result, ids) =>
      val json = if (result) s"""{"$ActionKey":"$ChangePasswordSucceeded"}""" else s"""{"$ActionKey":"$ChangePasswordFailed"}"""
      sendInformationToWebSockets(json, ids)
    case SendContactListInfo(contacts, ids) => sendInformationToWebSockets(Json.stringify(Json.toJson(contacts)), ids)
    case SendPresenceUpdate(presenceUpdate, ids) => sendInformationToWebSockets(Json.stringify(Json.toJson(presenceUpdate)), ids)
  }
}
