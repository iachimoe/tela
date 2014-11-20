package tela.web

import akka.actor.Actor
import play.api.libs.json.Json
import tela.web.JSONConversions._
import tela.web.WebSocketDataPusher._

object WebSocketDataPusher {

  case class CloseWebSockets(ids: Set[String])

  case class PushContactListInfoToWebSockets(contacts: AddContacts, ids: Set[String])

  case class PushPresenceUpdateToWebSockets(update: PresenceUpdate, ids: Set[String])

  case class PushCallSignalToWebSockets(callSignalReceipt: CallSignalReceipt, ids: Set[String])

}

class WebSocketDataPusher(sendInformationToWebSockets: (String, Iterable[String]) => Unit,
                          closeWebSockets: (Iterable[String]) => Unit) extends Actor {
  override def receive: Receive = {
    case CloseWebSockets(ids) => closeWebSockets(ids)
    case PushContactListInfoToWebSockets(contacts, ids) => sendInformationToWebSockets(Json.stringify(Json.toJson(contacts)), ids)
    case PushPresenceUpdateToWebSockets(presenceUpdate, ids) => sendInformationToWebSockets(Json.stringify(Json.toJson(presenceUpdate)), ids)
    case PushCallSignalToWebSockets(callSignalReceipt, ids) => sendInformationToWebSockets(Json.stringify(Json.toJson(callSignalReceipt)), ids)
  }
}
