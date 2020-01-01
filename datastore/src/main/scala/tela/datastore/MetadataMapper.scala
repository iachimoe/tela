package tela.datastore

import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneId}
import java.util.Date

import org.eclipse.rdf4j.model._
import org.eclipse.rdf4j.model.impl.{LinkedHashModel, SimpleValueFactory}
import org.eclipse.rdf4j.model.vocabulary.{GEO, RDF}
import tela.baseinterfaces.DataStoreConnection._
import tela.baseinterfaces.{ComplexObject, DataType, SimpleObject}
import tela.datastore.MetadataMapper._

import scala.jdk.CollectionConverters._
import scala.math.BigDecimal.RoundingMode

object MetadataMapper {
  private val Degrees = '°'
  private val Minutes = '\''
  private val Seconds = '\"'

  private[datastore] def gpsCoordsToWKTPoint(latitude: String, longitude: String): String = {
    val longitudeAsDecimal = coordinateAsDecimal(longitude)
    val latitudeAsDecimal = coordinateAsDecimal(latitude)
    s"POINT($longitudeAsDecimal $latitudeAsDecimal)"
  }

  //TODO One of the libraries being pulled in by tika (e.g. metadata-extractor) may be able to do this for us
  private def coordinateAsDecimal(coordinate: String): BigDecimal = {
    val decimalValue = sumComponents(convertAllComponentsToDegrees(convertToDMSComponents(coordinate)))
    if (decimalValue.scale > 5) decimalValue.setScale(5, RoundingMode.HALF_UP) else decimalValue
  }

  private def sumComponents(components: Vector[BigDecimal]): BigDecimal = {
    if (components.head >= 0) components.sum else components.reduceLeft(_ - _)
  }

  private def convertAllComponentsToDegrees(components: Vector[BigDecimal]): Vector[BigDecimal] = {
    components.zipWithIndex.map { case (component: BigDecimal, index: Int) => component / math.pow(60, index) }
  }

  private def convertToDMSComponents(coordinate: String): Vector[BigDecimal] = {
    coordinate.split(Array(Degrees, Minutes, Seconds)).map(s => BigDecimal(s.trim)).take(3).toVector
  }
}

class MetadataMapper(private val genericMediaItemDataMapping: ComplexObject, private val dataMapping: Map[String, ComplexObject]) {
  private val valueFactory = SimpleValueFactory.getInstance()

  //TODO Logging?
  def convertMetadataToRDF(uri: String, fileFormat: String, metadata: Map[String, String], hash: String): LinkedHashModel = {
    val combinedDataMapping = dataMapping.get(fileFormat).map(mappingForMediaType => {
      //TODO This is a questionable way to add fields from the generic map that are missing in the specific one
      ComplexObject(mappingForMediaType.objectType,
        mappingForMediaType.children ++ genericMediaItemDataMapping.children.flatMap {
          case mapping@(_, objectDefinition) => if (!mappingForMediaType.children.values.toVector.contains(objectDefinition)) Some(mapping) else None
        })
    }).getOrElse(genericMediaItemDataMapping)

    new LinkedHashModel(convertMetadataToRDF(valueFactory.createIRI(uri), combinedDataMapping, metadata + (HashPredicateKey -> hash) + (FileFormatPredicateKey -> fileFormat)).asJava)
  }

  private def convertMetadataToRDF(subject: Resource, objectSpec: ComplexObject, metadata: Map[String, String]): List[Statement] = {
    valueFactory.createStatement(subject, RDF.TYPE, valueFactory.createIRI(objectSpec.objectType.toString)) :: objectSpec.children.toList.flatMap({
      case (predicate, objectDefinition) =>
        objectDefinition match {
          case SimpleObject(properties, dataType) =>
            simpleObjectAsLiteral(dataType, properties, metadata).map(literal => valueFactory.createStatement(subject, valueFactory.createIRI(predicate.toString), literal)).toList
          case childObjectSpec: ComplexObject =>
            val subjectOfChildObject: BNode = valueFactory.createBNode()
            valueFactory.createStatement(subject, valueFactory.createIRI(predicate.toString), subjectOfChildObject) :: convertMetadataToRDF(subjectOfChildObject, childObjectSpec, metadata)
        }
    })
  }

  private def simpleObjectAsLiteral(dataType: DataType.Value, properties: Vector[String], metadata: Map[String, String]): Option[Literal] = {
    dataType match {
      case DataType.DateTime => metadata.get(properties.head).map(rawValue => valueFactory.createLiteral(Date.from(LocalDateTime.parse(rawValue, DateTimeFormatter.ISO_DATE_TIME).atZone(ZoneId.systemDefault()).toInstant)))
      case DataType.Geo =>
        for { //TODO This will blow up if the user hasn't set up their mapping configuration appropriately
          rawLat <- metadata.get(properties(0))
          rawLong <- metadata.get(properties(1))
        } yield valueFactory.createLiteral(gpsCoordsToWKTPoint(rawLat, rawLong), GEO.WKT_LITERAL)
      case _ => metadata.get(properties.head).map(valueFactory.createLiteral)
    }
  }
}
