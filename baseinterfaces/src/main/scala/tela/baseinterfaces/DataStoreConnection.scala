package tela.baseinterfaces

import java.net.URI
import java.nio.file.Path
import java.time.LocalDateTime
import scala.concurrent.Future

enum DataType {
  case Text, Bool, Decimal, Integer, DateTime, Geo
}

// How to handle multiple values of a field
// Aggregate simply adds multiple values to the parent entity
// Split creates multiple parent entities each with a single child value for that field
// There may be cases for Split where, rather than creating multiple instances
// of the immediate parent, instead multiple instances of a more remote ancestor should be created.
// But for now, we keep it simple...
// Furthermore, here are some constraints that possibly should be imposed, but currently are not:
// 1. Only one direct SimpleObject within the children of a ComplexObject can have a Split MultiplicityStrategy
// 2. We currently assume that the overall map being processed will correspond to a single object, which is the
// root of a tree potentially containing many generates of children, grandchildren etc. A consequence of this is that
// it doesn't make sense for the root level object definition to have any immediate children with a Split strategy.
enum MultiplicityStrategy {
  case Aggregate, Split
}

sealed trait RDFObjectDefinition

//TODO is children a Map or a List?
case class ComplexObject(objectType: URI, children: Map[URI, RDFObjectDefinition]) extends RDFObjectDefinition

case class SimpleObject(properties: Vector[String],
                        dataType: DataType = DataType.Text,
                        multiplicityStrategy: MultiplicityStrategy = MultiplicityStrategy.Aggregate
                       ) extends RDFObjectDefinition

object DataStoreConnection {
  val ObjectTypeKey = "objectType"
  val DataLocationPredicateKey = "dataLocationPredicate"
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
