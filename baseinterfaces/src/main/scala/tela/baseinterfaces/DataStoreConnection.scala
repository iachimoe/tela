package tela.baseinterfaces

import java.net.URI
import java.nio.file.Path
import java.time.LocalDateTime
import scala.concurrent.Future

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
  val HashPredicateKey = "hashPredicate" //TODO No longer just a hash, different name....
  val FileFormatPredicateKey = "fileFormatPredicate"
  val FileNamePredicateKey = "fileNamePredicate"
  val TextContentPredicateKey = "textContentPredicate"
}

trait DataStoreConnection {
  def closeConnection(): Future[Unit]

  def publish(uri: URI): Future[Unit]

  def insertJSON(data: String): Future[Unit]

  def retrieveJSON(uri: URI): Future[String]

  def retrievePublishedDataAsJSON(user: String, uri: URI): Future[String]

  def storeMediaItem(tempFileLocation: Path, originalFileName: Path, lastModified: Option[LocalDateTime]): Future[Unit]

  def retrieveMediaItem(hash: String): Future[Option[Path]]

  def runSPARQLQuery(query: String): Future[String]
}
