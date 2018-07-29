package tela.baseinterfaces

import java.net.URI
import java.nio.file.Path

object DataType extends Enumeration {
  val Text = Value("Text")
  val DateTime = Value("DateTime")
  val Geo = Value("Geo")
}

sealed trait RDFObjectDefinition

//TODO is children a Map or a List?
case class ComplexObject(objectType: URI, children: Map[URI, RDFObjectDefinition]) extends RDFObjectDefinition

case class SimpleObject(properties: Vector[String], dataType: DataType.Value = DataType.Text) extends RDFObjectDefinition

object DataStoreConnection {
  val MediaItemTypeKey = "mediaItemType" //TODO should be objectType because it's used for objects like Person
  val HashPredicateKey = "hashPredicate"
  val FileFormatPredicateKey = "fileFormatPredicate"
}

trait DataStoreConnection {
  def closeConnection(): Unit

  def publish(uri: URI): Unit

  def insertJSON(data: String): Unit

  def retrieveJSON(uri: URI): String

  def retrievePublishedDataAsJSON(user: String, uri: URI): String

  def storeMediaItem(tempFileLocation: Path, originalFileName: Path): Unit

  def retrieveMediaItem(hash: String): Option[Path]

  def runSPARQLQuery(query: String): String

  def textSearch(query: String): Vector[String]
}
