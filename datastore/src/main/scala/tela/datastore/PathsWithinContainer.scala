package tela.datastore

import tela.datastore.PathsWithinContainer.TikaPathInfo

import java.nio.file.Path

object PathsWithinContainer {
  case class TikaPathInfo(internalPath: String, embeddedResourcePath: String)
}

class PathsWithinContainer(private val allPaths: Vector[TikaPathInfo]) {
  private val pathsMap = allPaths.map(path => path.embeddedResourcePath -> path.internalPath).toMap

  def getCompletePath(pathInfo: TikaPathInfo): String = {
    val parent = Path.of(pathInfo.embeddedResourcePath).getParent
    if (parent.getRoot == parent) {
      parent.resolve(pathInfo.internalPath).toString.substring(1)
    } else {
      Path.of(getCompletePath(TikaPathInfo(pathsMap(parent.toString), parent.toString))).resolve(pathInfo.internalPath).toString
    }
  }

  override def toString: String = s"""PathsWithinContainer(${allPaths.toString})"""
}
