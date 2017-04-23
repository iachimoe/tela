package tela.baseinterfaces

import java.net.URI

object DataType extends Enumeration {
  val Text = Value("Text")
  val DateTime = Value("DateTime")
  val Geo = Value("Geo")
}

sealed trait RDFObjectDefinition

//TODO is children a Map or a List?
case class ComplexObject(objectType: URI, children: Map[URI, RDFObjectDefinition]) extends RDFObjectDefinition

case class SimpleObject(properties: List[String], dataType: DataType.Value = DataType.Text) extends RDFObjectDefinition

object DataStoreConnection {
  val MediaItemTypeKey = "mediaItemType" //TODO should be objectType because it's used for objects like Person
  val HashPredicateKey = "hashPredicate"
  val FileFormatPredicateKey = "fileFormatPredicate"
}

trait DataStoreConnection {
  def closeConnection(): Unit

  def publish(uri: String): Unit

  def insertJSON(data: String): Unit

  def retrieveJSON(uri: String): String

  def retrievePublishedDataAsJSON(user: String, uri: String): String

  def storeMediaItem(tempFileLocation: String, originalFileName: String): Unit

  def retrieveMediaItem(hash: String): Option[String]

  def runSPARQLQuery(query: String): String

  def textSearch(query: String): List[String]
}
