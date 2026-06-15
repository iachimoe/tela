package tela.datastore

import org.apache.tika.mime.MediaType

import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneId, ZoneOffset}
import java.util.{GregorianCalendar, TimeZone}
import org.eclipse.rdf4j.model.*
import org.eclipse.rdf4j.model.impl.{LinkedHashModel, SimpleValueFactory}
import org.eclipse.rdf4j.model.vocabulary.{GEO, RDF}
import tela.baseinterfaces.DataStoreConnection.*
import tela.baseinterfaces.{ComplexObject, DataType, MultiplicityStrategy, RDFObjectDefinition, SimpleObject}
import tela.datastore.MetadataMapper.*

import java.net.URI
import javax.xml.datatype.DatatypeFactory
import scala.jdk.CollectionConverters.*
import scala.math.BigDecimal.RoundingMode
import scala.util.Try

object MetadataMapper {
  private[datastore] val GeoCoordinatesLiteralType = GEO.WKT_LITERAL
  private[datastore] val OctetStreamContentType = "application/octet-stream"

  private val datatypeFactory = DatatypeFactory.newInstance

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
  private val calendar = new GregorianCalendar
  calendar.setTimeZone(TimeZone.getTimeZone(ZoneId.of("UTC")))

  def convertMetadataToRDF(uri: String, maybeFileFormat: Option[String], metadata: Map[String, Vector[String]], textContent: Option[String], hash: String, filename: String): LinkedHashModel = {
    val fileFormat = maybeFileFormat.getOrElse(OctetStreamContentType)
    val combinedDataMapping = dataMapping.get(MediaType.parse(fileFormat).getBaseType.toString).map(mappingForMediaType => {
      ComplexObject(mappingForMediaType.objectType,
        mappingForMediaType.children ++
          genericMediaItemDataMapping.children.filterNot(mapping => mappingForMediaType.children.values.toVector.contains(mapping._2)))
    }).getOrElse(genericMediaItemDataMapping)

    val baseKeys = Vector(Some(DataLocationPredicateKey -> hash),
      Some(FileNamePredicateKey -> filename),
      Some(FileFormatPredicateKey -> fileFormat),
      textContent.map(TextContentPredicateKey -> _)
    ).flatten.toMap.view.mapValues(s => Vector(s)).toMap

    new LinkedHashModel(convertMetadataToRDF(valueFactory.createIRI(uri), combinedDataMapping, metadata ++ baseKeys).asJava)
  }

  private def findPropertyWithSplitMultiplicityStrategy(objectSpec: ComplexObject): Option[Vector[String]] = {
    objectSpec.children.collectFirst {
      case (uri, SimpleObject(properties, _, MultiplicityStrategy.Split)) => properties
    }
  }

  private def splitProperties(properties: Vector[String], metadata: Map[String, Vector[String]]): Vector[Map[String, Vector[String]]] = {
    properties.map(property => metadata.get(property).toVector.flatten.map(property -> Vector(_))).transpose.map(_.toMap)
  }

  private def convertMetadataToRDF(subject: Resource, objectSpec: ComplexObject, metadata: Map[String, Vector[String]]): Vector[Statement] = {
    def createChildObjectAttachedToParent(parentSubject: Resource,
                                          relationPredicate: URI,
                                          childObjectSpec: ComplexObject,
                                          metadata: Map[String, Vector[String]]) = {
      val subjectOfChildObject: BNode = valueFactory.createBNode()
      val childStatements = convertMetadataToRDF(subjectOfChildObject, childObjectSpec, metadata)

      if (childStatements.isEmpty)
        Vector.empty
      else
        valueFactory.createStatement(parentSubject, valueFactory.createIRI(relationPredicate.toString), subjectOfChildObject) +: childStatements
    }

    def generateStatementsForObjectDefinition(predicate: URI, objectDefinition: RDFObjectDefinition) = {
      objectDefinition match {
        case SimpleObject(properties, dataType, _) =>
          simpleObjectVectorAsLiterals(dataType, properties, metadata).map(literal => valueFactory.createStatement(subject, valueFactory.createIRI(predicate.toString), literal)).toVector
        case childObjectSpec: ComplexObject =>
          findPropertyWithSplitMultiplicityStrategy(childObjectSpec) match {
            case Some(properties) =>
              // Tricky to see what's going on here, but basically we convert a key with
              // multiple values into a Vector of identical keys, each with a single value.
              // Then for each item we create a child object.
              splitProperties(properties, metadata).flatMap(current => {
                createChildObjectAttachedToParent(subject, predicate, childObjectSpec, metadata ++ current)
              })
            case None =>
              createChildObjectAttachedToParent(subject, predicate, childObjectSpec, metadata)
          }
      }
    }

    val childStatements = objectSpec.children.toVector.flatMap {
      case (predicate, objectDefinition) => generateStatementsForObjectDefinition(predicate, objectDefinition)
    }

    //TODO There may be cases where a different type predicate is desired, so this should be configurable
    if (childStatements.isEmpty) Vector.empty
    else valueFactory.createStatement(subject, RDF.TYPE, valueFactory.createIRI(objectSpec.objectType.toString)) +: childStatements
  }

  private def simpleObjectVectorAsLiterals(dataType: DataType, properties: Vector[String], metadata: Map[String, Vector[String]]): Vector[Literal] = {
    dataType match {
      case DataType.DateTime => getUTCDateTimeLiteralsFromMetadata(metadata, properties)
      case DataType.Geo => getGeoLiteralsFromMetadata(metadata, properties)
      case DataType.Integer => literalsFromStrings(properties, metadata, _.toIntOption.map(valueFactory.createLiteral))
      case DataType.Bool => literalsFromStrings(properties, metadata, _.toBooleanOption.map(valueFactory.createLiteral))
      case DataType.Decimal => literalsFromStrings(properties, metadata, s => Try(BigDecimal(s)).toOption.map(bd => valueFactory.createLiteral(bd.bigDecimal)))
      case DataType.Text => literalsFromStrings(properties, metadata, s => Option(s).map(valueFactory.createLiteral))
    }
  }

  private def literalsFromStrings(properties: Vector[String], metadata: Map[String, Vector[String]], stringToMaybeLiteral: String => Option[Literal]): Vector[Literal] = {
    properties.headOption.flatMap(metadata.get).map(_.flatMap(stringToMaybeLiteral)).getOrElse(Vector.empty)
  }

  //TODO I believe that because we are explicitly setting the timezone on the calendar object to UTC on construction,
  //we are potentially assuming that the given time is UTC regardless of the original
  //timezone, thus potentially storing incorrect information about non-UTC times.
  //The best approach might be to switch from using LocalDateTime to ZonedDateTime
  private def getUTCDateTimeLiteralsFromMetadata(metadata: Map[String, Vector[String]], properties: Vector[String]): Vector[Literal] =
    properties.headOption.flatMap(metadata.get).map(rawValues => {
      rawValues.map(rawValue => {
        calendar.setTimeInMillis(LocalDateTime.parse(rawValue, DateTimeFormatter.ISO_DATE_TIME).toInstant(ZoneOffset.UTC).toEpochMilli)
        valueFactory.createLiteral(datatypeFactory.newXMLGregorianCalendar(calendar))
      })
    }).getOrElse(Vector.empty)

  private def getGeoLiteralsFromMetadata(metadata: Map[String, Vector[String]], properties: Vector[String]): Vector[Literal] = {
    //TODO This will blow up if the user hasn't set up their mapping configuration appropriately (i.e. it doesn't have two elements in properties)
    val rawLats = metadata.getOrElse(properties(0), Vector.empty)
    val rawLongs = metadata.getOrElse(properties(1), Vector.empty)
    (rawLats zip rawLongs).map((rawLat, rawLong) => {
      valueFactory.createLiteral(gpsCoordsToWKTPoint(rawLat, rawLong), GeoCoordinatesLiteralType)
    })
  }
}
