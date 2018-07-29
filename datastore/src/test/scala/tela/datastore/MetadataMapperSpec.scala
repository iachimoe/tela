package tela.datastore

import java.net.URI
import java.time.{LocalDateTime, ZoneId}
import java.util.{Date, Optional}

import org.eclipse.rdf4j.model._
import org.eclipse.rdf4j.model.impl.{LinkedHashModel, SimpleValueFactory}
import org.eclipse.rdf4j.model.util.Models
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.scalatest.Matchers._
import tela.baseinterfaces.DataStoreConnection._
import tela.baseinterfaces._

import scala.collection.JavaConverters._

class MetadataMapperSpec extends DataStoreBaseSpec {
  private val valueFactory = SimpleValueFactory.getInstance

  "gpsCoordsToWKTPoint" should "convert GPS Coordinates to a WKT Point" in {
    MetadataMapper.gpsCoordsToWKTPoint("51.56", "-87.0° 37.0' 28.17839999999478\"") should === ("POINT(-87.62449 51.56)")
  }

  "convertMetadataToRDF" should "extract hash and type information from input" in {
    val result = new MetadataMapper(GenericFileDataMap, Map()).convertMetadataToRDF("http://test", "audio/mp4", Map(), "testHash")
    result.size() should === (3)
    assertSingleStringObject("testHash", result, new URI("http://test"), HashPredicate)
    assertSingleURIObject(GenericMediaFileType, result, new URI("http://test"), new URI(RDF.TYPE.toString))
    assertSingleStringObject("audio/mp4", result, new URI("http://test"), FileFormatPredicate)
  }

  it should "override generic mapping when specific mapping exists for field" in {
    val specificFileDataMap = ComplexObject(new URI("http://me.org/contrarianType"), Map(
      new URI("http://schema.org/urlWasABadChoiceAnyway") -> SimpleObject(Vector(HashPredicateKey)),
      new URI("http://justTellMeTheFreakinFileFormat") -> SimpleObject(Vector(FileFormatPredicateKey))))

    val result = new MetadataMapper(GenericFileDataMap, Map("audio/mp4" -> specificFileDataMap)).convertMetadataToRDF("http://test", "audio/mp4", Map(), "testHash")
    result.size() should === (3)
    assertSingleStringObject("testHash", result, new URI("http://test"), new URI("http://schema.org/urlWasABadChoiceAnyway"))
    assertSingleURIObject(new URI("http://me.org/contrarianType"), result, new URI("http://test"), new URI(RDF.TYPE.toString))
    assertSingleStringObject("audio/mp4", result, new URI("http://test"), new URI("http://justTellMeTheFreakinFileFormat"))
  }

  it should "map text fields" in {
    val metadata = Map("name" -> "Bob Dylan", "genre" -> "Rock")
    val map = ComplexObject(MP3ObjectType, Map(new URI("http://schema.org/name") -> SimpleObject(Vector("name")), new URI("http://schema.org/genre") -> SimpleObject(Vector("genre"))))
    val result = new MetadataMapper(GenericFileDataMap, Map("audio/mp4" -> map)).convertMetadataToRDF("http://test", "audio/mp4", metadata, "testHash")

    result.size() should === (5)
    assertSingleStringObject("Bob Dylan", result, new URI("http://test"), new URI("http://schema.org/name"))
    assertSingleStringObject("Rock", result, new URI("http://test"), new URI("http://schema.org/genre"))
  }

  it should "create RDF Blank node when mapping is to ComplexObject" in {
    val metadata = Map("xmpDM:artist" -> "Bob Dylan")

    val result = new MetadataMapper(GenericFileDataMap, Map("audio/mp4" ->
      ComplexObject(MP3ObjectType, Map(
        new URI("http://schema.org/author") -> ComplexObject(new URI("http://schema.org/Person"), Map(new URI("http://schema.org/name") -> SimpleObject(Vector("xmpDM:artist"))))
      )))
    ).convertMetadataToRDF("http://test", "audio/mp4", metadata, "testHash")
    result.size() should === (6)

    val expectedAuthor: Resource = Models.objectResource(result.filter(valueFactory.createIRI("http://test"), valueFactory.createIRI("http://schema.org/author"), null)).get
    expectedAuthor.isInstanceOf[BNode] should === (true)
    Models.objectIRI(result.filter(expectedAuthor, RDF.TYPE, null)) should === (Optional.of(valueFactory.createIRI("http://schema.org/Person")))
    Models.`object`(result.filter(expectedAuthor, valueFactory.createIRI("http://schema.org/name"), null)) should === (Optional.of[Value](valueFactory.createLiteral("Bob Dylan")))
  }

  it should "map dates" in {
    val metadata = Map("Creation-Date" -> "2010-08-08T15:35:33Z")

    val result = new MetadataMapper(GenericFileDataMap, Map(
      MP3ContentType -> ComplexObject(MP3ObjectType, Map(new URI("http://schema.org/dateCreated") -> SimpleObject(Vector("Creation-Date"), DataType.DateTime))))
    ).convertMetadataToRDF("http://test", MP3ContentType, metadata, "testHash")

    assertSingleDateObject("2010-08-08T15:35:33", result, new URI("http://test"), new URI("http://schema.org/dateCreated"))
  }

  it should "map GPS coordinates to GeoSPARQL compatible values" in {
    val metadata = Map("GPS Longitude" -> "-87.0° 37.0' 28.17839999999478\"", "GPS Latitude" -> "41.0° 53.0' 20.21279999999706\"")

    val result = new MetadataMapper(GenericFileDataMap, Map("thing/place" ->
      ComplexObject(new URI("http://schema.org/Place"),
        Map(new URI("http://www.opengis.net/ont/geosparql#asWKT") -> SimpleObject(Vector("GPS Latitude", "GPS Longitude"), DataType.Geo)))
    )).convertMetadataToRDF("http://test", "thing/place", metadata, "testHash")

    val expectedResult = new LinkedHashModel()
    expectedResult.add(valueFactory.createIRI("http://test"), RDF.TYPE, valueFactory.createIRI("http://schema.org/Place"))
    expectedResult.add(valueFactory.createIRI("http://test"), valueFactory.createIRI("http://www.opengis.net/ont/geosparql#asWKT"),
      valueFactory.createLiteral("POINT(-87.62449 41.88895)", valueFactory.createIRI("http://www.opengis.net/ont/geosparql#wktLiteral")))

    expectedResult.asScala.forall(result.contains) should === (true)
  }

  it should "ignore fields in the mapping that are missing from the data" in {
    val metadata = Map("name" -> "Bob Dylan")
    val map = ComplexObject(MP3ObjectType, Map(
      new URI("http://schema.org/name") -> SimpleObject(Vector("name")),
      new URI("http://schema.org/genre") -> SimpleObject(Vector("genre")),
      new URI("http://schema.org/dateCreated") -> SimpleObject(Vector("Creation-Date"), DataType.DateTime),
      new URI("http://www.opengis.net/ont/geosparql#asWKT") -> SimpleObject(Vector("GEO"), DataType.Geo)
    ))
    val result = new MetadataMapper(GenericFileDataMap, Map("audio/mp4" -> map)).convertMetadataToRDF("http://test", "audio/mp4", metadata, "testHash")

    result.size() should === (4)
    assertSingleStringObject("Bob Dylan", result, new URI("http://test"), new URI("http://schema.org/name"))
  }

  private def assertSingleStringObject(expectedObjectValue: String, model: Model, subject: URI, predicate: URI): Unit = {
    val filtered: Model = model.filter(valueFactory.createIRI(subject.toString), valueFactory.createIRI(predicate.toString), null)
    filtered.size() should === (1)
    Models.`object`(filtered) should === (Optional.of[Value](valueFactory.createLiteral(expectedObjectValue)))
  }

  private def assertSingleURIObject(expectedObjectURI: URI, model: Model, subject: URI, predicate: URI): Unit = {
    val filtered: Model = model.filter(valueFactory.createIRI(subject.toString), valueFactory.createIRI(predicate.toString), null)
    filtered.size() should === (1)
    Models.`object`(filtered) should === (Optional.of[Value](valueFactory.createIRI(expectedObjectURI.toString)))
  }

  private def assertSingleDateObject(expectedDateAsString: String, model: Model, subject: URI, predicate: URI): Unit = {
    val filtered: Model = model.filter(valueFactory.createIRI(subject.toString), valueFactory.createIRI(predicate.toString), null)
    filtered.size() should === (1)
    Models.`object`(filtered) should === (Optional.of[Value](valueFactory.createLiteral(Date.from(LocalDateTime.parse(expectedDateAsString).atZone(ZoneId.systemDefault()).toInstant))))
  }
}
