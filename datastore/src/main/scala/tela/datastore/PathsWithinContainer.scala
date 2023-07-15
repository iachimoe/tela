package tela.datastore

import tela.datastore.PathsWithinContainer.TikaPathInfo

import java.nio.file.Path

object PathsWithinContainer {
  case class TikaPathInfo(embeddedRelationshipId: String, embeddedResourcePath: String)
}

class PathsWithinContainer(private val allPaths: Vector[TikaPathInfo]) {
  private val pathsMap = allPaths.map(path => path.embeddedResourcePath -> path.embeddedRelationshipId).toMap

  def getCompletePath(pathInfo: TikaPathInfo): String = {
    val parent = Path.of(pathInfo.embeddedResourcePath).getParent
    if (parent.getRoot == parent) {
      parent.resolve(pathInfo.embeddedRelationshipId).toString.substring(1)
    } else {
      val embeddedRelationshipId = pathsMap(parent.toString)
      Path.of(getCompletePath(TikaPathInfo(embeddedRelationshipId, parent.toString))).resolve(pathInfo.embeddedRelationshipId).toString
    }
  }
}
