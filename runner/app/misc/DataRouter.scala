package misc

import javax.inject.Inject

import play.api.mvc.RequestHeader
import play.api.routing.Router
import play.api.routing.Router.Routes
import play.api.routing.sird._
import tela.web.DataController

class DataRouter @Inject()(controller: DataController) extends ModifiedSimpleRouter
{
  override def routes: Routes = {
    case PUT(q"uriToPublish=$uriToPublish") => controller.publishData(uriToPublish)
    case PUT(_) => controller.uploadMediaItem()
    case GET(q"hash=$hash") => controller.downloadMediaItem(hash)
    case GET(q"query=$query") => controller.sparqlQuery(query)
    case GET(q"text=$text") => controller.textSearch(text)
    case GET(q"uri=$uri" & q_o"publisher=$publisher") => controller.retrieveData(uri, publisher)
  }
}

//The standard SimpleRouter seems to have a bug, hopefully this will be fixed at a later date...
//For now, this is a modified version of SimpleRouter from the play source
trait ModifiedSimpleRouter extends Router { self =>
  def documentation: Seq[(String, String, String)] = Seq.empty
  def withPrefix(prefix: String): Router = {
    if (prefix == "/") {
      self
    } else {
      new Router {
        def routes = {
          val p = prefix
          val prefixed: PartialFunction[RequestHeader, RequestHeader] = {
            case rh: RequestHeader if rh.path.startsWith(p) => rh.copy(path = rh.path.drop(p.length - 1))
          }
          Function.unlift(prefixed.lift.andThen(_.flatMap(self.routes.lift)))
        }
        def withPrefix(prefix: String) = self.withPrefix(prefix)
        def documentation = self.documentation
      }
    }
  }
}
