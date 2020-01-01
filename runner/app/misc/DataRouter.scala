package misc

import javax.inject.Inject
import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter
import play.api.routing.sird._
import tela.web.DataController

class DataRouter @Inject()(controller: DataController) extends SimpleRouter
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
