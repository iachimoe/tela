package tela.web

import akka.actor.{Actor, ActorLogging}
import org.mashupbots.socko.events.HttpRequestEvent
import org.webjars.WebJarAssetLocator

import scala.io.Source

class WebJarHandler(filename: String) extends Actor with ActorLogging {
  override def receive: Receive = {
    case event: HttpRequestEvent =>
      event.response.write(Source.fromInputStream(getClass.getClassLoader.getResourceAsStream(new WebJarAssetLocator().getFullPath(filename))).mkString.getBytes)
      context.stop(self)
  }
}
