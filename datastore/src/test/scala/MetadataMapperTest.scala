package tela.datastore

import java.net.URI
import java.time.{LocalDateTime, ZoneId}
import java.util
import java.util.Date

import org.junit.Assert._
import org.junit.Test
import org.eclipse.rdf4j.model._
import org.eclipse.rdf4j.model.impl.{LinkedHashModel, SimpleValueFactory}
import org.eclipse.rdf4j.model.util.Models
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.scalatest.junit.AssertionsForJUnit
import tela.baseinterfaces.DataStoreConnection._
import tela.baseinterfaces.{SimpleObject, _}
import tela.datastore.TestData._

class MetadataMapperTest extends AssertionsForJUnit {
  private val valueFactory = SimpleValueFactory.getInstance

  @Test def hashAndTypeInformation(): Unit = {
    val result = new MetadataMapper(GenericFileDataMap, Map()).convertMetadataToRDF("http://test", "audio/mp4", Map(), "testHash")
    assertEquals(3, result.size())
    assertSingleStringObject("testHash", result, new URI("http://test"), HashPredicate)
    assertSingleURIObject(GenericMediaFileType, result, new URI("http://test"), new URI(RDF.TYPE.toString))
    assertSingleStringObject("audio/mp4", result, new URI("http://test"), FileFormatPredicate)
  }

  @Test def genericMappingIsOverriddenByFileFormatSpecificMapping(): Unit = {
    val specificFileDataMap = ComplexObject(new URI("http://me.org/contrarianType"), Map(
      new URI("http://schema.org/urlWasABadChoiceAnyway") -> SimpleObject(List(HashPredicateKey)),
        new URI("http://justTellMeTheFreakinFileFormat") -> SimpleObject(List(FileFormatPredicateKey))))

    val result = new MetadataMapper(GenericFileDataMap, Map("audio/mp4" -> specificFileDataMap)).convertMetadataToRDF("http://test", "audio/mp4", Map(), "testHash")
    assertEquals(3, result.size())
    assertSingleStringObject("testHash", result, new URI("http://test"), new URI("http://schema.org/urlWasABadChoiceAnyway"))
    assertSingleURIObject(new URI("http://me.org/contrarianType"), result, new URI("http://test"), new URI(RDF.TYPE.toString))
    assertSingleStringObject("audio/mp4", result, new URI("http://test"), new URI("http://justTellMeTheFreakinFileFormat"))
  }

  @Test def textFields(): Unit = {
    val metadata = Map("name" -> "Bob Dylan", "genre" -> "Rock")
    val map = ComplexObject(MP3ObjectType, Map(new URI("http://schema.org/name") -> SimpleObject(List("name")), new URI("http://schema.org/genre") -> SimpleObject(List("genre"))))
    val result = new MetadataMapper(GenericFileDataMap, Map("audio/mp4" -> map)).convertMetadataToRDF("http://test", "audio/mp4", metadata, "testHash")

    assertEquals(5, result.size())
    assertSingleStringObject("Bob Dylan", result, new URI("http://test"), new URI("http://schema.org/name"))
    assertSingleStringObject("Rock", result, new URI("http://test"), new URI("http://schema.org/genre"))
  }

  @Test def childNode(): Unit = {
    val metadata = Map("xmpDM:artist" -> "Bob Dylan")

    val result = new MetadataMapper(GenericFileDataMap, Map("audio/mp4" ->
      ComplexObject(MP3ObjectType, Map(
        new URI("http://schema.org/author") -> ComplexObject(new URI("http://schema.org/Person"), Map(new URI("http://schema.org/name") -> SimpleObject(List("xmpDM:artist"))))
      )))
    ).convertMetadataToRDF("http://test", "audio/mp4", metadata, "testHash")
    assertEquals(6, result.size())

    val expectedAuthor: Resource = Models.objectResource(result.filter(valueFactory.createIRI("http://test"), valueFactory.createIRI("http://schema.org/author"), null)).get
    assertTrue(expectedAuthor.isInstanceOf[BNode])
    assertEquals(valueFactory.createIRI("http://schema.org/Person"), Models.objectIRI(result.filter(expectedAuthor, RDF.TYPE, null)).get)
    assertEquals(valueFactory.createLiteral("Bob Dylan"), Models.`object`(result.filter(expectedAuthor, valueFactory.createIRI("http://schema.org/name"), null)).get)
  }

  @Test def date(): Unit = {
    val metadata = Map("Creation-Date" -> "2010-08-08T15:35:33Z")

    val result = new MetadataMapper(GenericFileDataMap, Map(
      MP3ContentType -> ComplexObject(MP3ObjectType, Map(new URI("http://schema.org/dateCreated") -> SimpleObject(List("Creation-Date"), DataType.DateTime))))
    ).convertMetadataToRDF("http://test", MP3ContentType, metadata, "testHash")

    assertSingleDateObject("2010-08-08T15:35:33", result, new URI("http://test"), new URI("http://schema.org/dateCreated"))
  }

  @Test def gpsCoordsToWKTPoint(): Unit = {
    assertEquals("POINT(-87.62449 51.56)", MetadataMapper.gpsCoordsToWKTPoint("51.56", "-87.0° 37.0' 28.17839999999478\""))
  }

  @Test def geographicLocation(): Unit = {
    val metadata = Map("GPS Longitude" -> "-87.0° 37.0' 28.17839999999478\"", "GPS Latitude" -> "41.0° 53.0' 20.21279999999706\"")

    val result = new MetadataMapper(GenericFileDataMap, Map("thing/place" ->
      ComplexObject(new URI("http://schema.org/Place"),
        Map(new URI("http://www.opengis.net/ont/geosparql#asWKT") -> SimpleObject(List("GPS Latitude", "GPS Longitude"), DataType.Geo)))
        )).convertMetadataToRDF("http://test", "thing/place", metadata, "testHash")

    val expectedResult = new LinkedHashModel()
    expectedResult.add(valueFactory.createIRI("http://test"), RDF.TYPE, valueFactory.createIRI("http://schema.org/Place"))
    expectedResult.add(valueFactory.createIRI("http://test"), valueFactory.createIRI("http://www.opengis.net/ont/geosparql#asWKT"),
      valueFactory.createLiteral("POINT(-87.62449 41.88895)", valueFactory.createIRI("http://www.opengis.net/ont/geosparql#wktLiteral")))

    //TODO Find a nicer way to do this assertion
    val iterator: util.Iterator[_] = expectedResult.iterator()
    while (iterator.hasNext) {
      val next: Statement = iterator.next().asInstanceOf[Statement]
      assertTrue(result.contains(next))
    }
  }

  @Test def missingFieldsAreIgnored(): Unit = {
    val metadata = Map("name" -> "Bob Dylan")
    val map = ComplexObject(MP3ObjectType, Map(
      new URI("http://schema.org/name") -> SimpleObject(List("name")),
      new URI("http://schema.org/genre") -> SimpleObject(List("genre")),
      new URI("http://schema.org/dateCreated") -> SimpleObject(List("Creation-Date"), DataType.DateTime),
      new URI("http://www.opengis.net/ont/geosparql#asWKT") -> SimpleObject(List("GEO"), DataType.Geo)
    ))
    val result = new MetadataMapper(GenericFileDataMap, Map("audio/mp4" -> map)).convertMetadataToRDF("http://test", "audio/mp4", metadata, "testHash")

    assertEquals(4, result.size())
    assertSingleStringObject("Bob Dylan", result, new URI("http://test"), new URI("http://schema.org/name"))
  }

  private def assertSingleStringObject(expectedObjectValue: String, model: Model, subject: URI, predicate: URI): Unit = {
    val filtered: Model = model.filter(valueFactory.createIRI(subject.toString), valueFactory.createIRI(predicate.toString), null)
    assertEquals(1, filtered.size())
    assertEquals(valueFactory.createLiteral(expectedObjectValue), Models.`object`(filtered).get())
  }

  private def assertSingleURIObject(expectedObjectURI: URI, model: Model, subject: URI, predicate: URI): Unit = {
    val filtered: Model = model.filter(valueFactory.createIRI(subject.toString), valueFactory.createIRI(predicate.toString), null)
    assertEquals(1, filtered.size())
    assertEquals(valueFactory.createIRI(expectedObjectURI.toString), Models.`object`(filtered).get())
  }

  private def assertSingleDateObject(expectedDateAsString: String, model: Model, subject: URI, predicate: URI): Unit = {
    val filtered: Model = model.filter(valueFactory.createIRI(subject.toString), valueFactory.createIRI(predicate.toString), null)
    assertEquals(1, filtered.size())
    assertEquals(valueFactory.createLiteral(Date.from(LocalDateTime.parse(expectedDateAsString).atZone(ZoneId.systemDefault()).toInstant)), Models.`object`(filtered).get())
  }
}
