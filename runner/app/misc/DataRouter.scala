package misc

import javax.inject.Inject
import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter
import play.api.routing.sird._
import tela.web.DataController

class DataRouter @Inject()(controller: DataController) extends SimpleRouter
{
  override def routes: Routes = {
    case PUT(q"uri=$uri") => controller.publishData(uri)
    case PUT(_) => controller.uploadMediaItem()
    case GET(q"query=$query") => controller.sparqlQuery(query)
    case GET(q"uri=$uri" & q_o"publisher=$publisher") => controller.retrieveData(uri, publisher)
    case GET(p"/$hash/$childPath*") => controller.downloadMediaItem(hash, childPath)
    case GET(p"/$hash") => controller.downloadMediaItem(hash)
  }
}
