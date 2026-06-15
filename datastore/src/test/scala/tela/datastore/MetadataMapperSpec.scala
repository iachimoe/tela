package tela.datastore

import org.apache.tika.metadata.TikaCoreProperties

import java.net.URI
import java.util.{GregorianCalendar, Optional}
import org.eclipse.rdf4j.model.*
import org.eclipse.rdf4j.model.datatypes.XMLDatatypeUtil
import org.eclipse.rdf4j.model.impl.{LinkedHashModel, SimpleValueFactory}
import org.eclipse.rdf4j.model.util.{ModelBuilder, Models}
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.eclipse.rdf4j.model.util.Values.*
import org.scalatest.matchers.should.Matchers.*
import tela.baseinterfaces.DataStoreConnection.*
import tela.baseinterfaces.*
import tela.baseinterfaces.MultiplicityStrategy.{Aggregate, Split}
import tela.datastore.MetadataMapper.{GeoCoordinatesLiteralType, OctetStreamContentType}

import javax.xml.datatype.DatatypeFactory
import scala.jdk.CollectionConverters.*

class MetadataMapperSpec extends DataStoreBaseSpec {
  private val valueFactory = SimpleValueFactory.getInstance

  "gpsCoordsToWKTPoint" should "convert GPS Coordinates to a WKT Point" in {
    MetadataMapper.gpsCoordsToWKTPoint("51.56", "-87.0° 37.0' 28.17839999999478\"") should === ("POINT(-87.62449 51.56)")
  }

  "convertMetadataToRDF" should "extract hash and type information from input" in {
    val result = new MetadataMapper(GenericFileDataMap, Map()).convertMetadataToRDF(URNWithTestUUID, Some(MP3ContentType), Map(), None, HashOfTestMP3, TestMP3FileName.toString)
    result.size() should === (4)
    assertSingleStringObject(HashOfTestMP3, result, URNWithTestUUIDAsURI, DataLocationPredicate)
    assertSingleStringObject(TestMP3FileName.toString, result, URNWithTestUUIDAsURI, FileNamePredicate)
    assertSingleURIObject(GenericMediaFileType, result, URNWithTestUUIDAsURI, new URI(RDF.TYPE.toString))
    assertSingleStringObject(MP3ContentType, result, URNWithTestUUIDAsURI, FileFormatPredicate)
  }

  it should "include text content in resulting graph if provided" in {
    val result = new MetadataMapper(GenericFileDataMap, Map()).convertMetadataToRDF(URNWithTestUUID, Some(PlainTextContentType), Map(), Some("Here is some text"), HashOfTestTextFile, TestTextFileName.toString)
    result.size() should ===(5)
    assertSingleStringObject("Here is some text", result, URNWithTestUUIDAsURI, TextContentPredicate)
  }

  it should "ignore charset of content type for purposes of identifying data map" in {
    val result = new MetadataMapper(GenericFileDataMap, Map(ICalContentType -> ICalDataMap)).convertMetadataToRDF(URNWithTestUUID, Some(s"$ICalContentType; charset=windows-1252"), Map(TikaCoreProperties.TITLE.getName -> Vector("My event")), None, HashOfTestTextFile, TestTextFileName.toString)

    result.size() should === (5)
    assertSingleStringObject("My event", result, URNWithTestUUIDAsURI, NamePredicate)
  }

  it should "override generic mapping when specific mapping exists for field" in {
    val specificFileDataMap = ComplexObject(new URI("http://me.org/contrarianType"), Map(
      SchemaDotOrgBase.resolve("urlWasABadChoiceAnyway") -> SimpleObject(Vector(DataLocationPredicateKey)),
      new URI("http://justTellMeTheFreakinFileFormat") -> SimpleObject(Vector(FileFormatPredicateKey))))

    val result = new MetadataMapper(GenericFileDataMap, Map(MP3ContentType -> specificFileDataMap)).convertMetadataToRDF(URNWithTestUUID, Some(MP3ContentType), Map(), None, HashOfTestMP3, TestMP3FileName.toString)
    result.size() should === (4)
    assertSingleStringObject(HashOfTestMP3, result, URNWithTestUUIDAsURI, SchemaDotOrgBase.resolve("urlWasABadChoiceAnyway"))
    assertSingleStringObject(TestMP3FileName.toString, result, URNWithTestUUIDAsURI, FileNamePredicate)
    assertSingleURIObject(new URI("http://me.org/contrarianType"), result, URNWithTestUUIDAsURI, new URI(RDF.TYPE.toString))
    assertSingleStringObject(MP3ContentType, result, URNWithTestUUIDAsURI, new URI("http://justTellMeTheFreakinFileFormat"))
  }

  it should "map text fields" in {
    val metadata = Map("name" -> Vector("Bob Dylan"), "genre" -> Vector("Rock"))
    val map = ComplexObject(MP3ObjectType, Map(NamePredicate -> SimpleObject(Vector("name")), GenrePredicate -> SimpleObject(Vector("genre"))))
    val result = new MetadataMapper(GenericFileDataMap, Map(MP3ContentType -> map)).convertMetadataToRDF(
      URNWithTestUUID, Some(MP3ContentType), metadata, None, HashOfTestMP3, TestMP3FileName.toString)

    result.size() should === (6)
    assertSingleStringObject("Bob Dylan", result, URNWithTestUUIDAsURI, NamePredicate)
    assertSingleStringObject("Rock", result, URNWithTestUUIDAsURI, GenrePredicate)
  }

  it should "map boolean, decimal and integer fields, omitting invalid values" in {
    val metadata = Map("anInt" -> Vector("654", "143", "invalidInt"),
      "aDecimal" -> Vector("2.71828", "42.001", "invalidDecimal"),
      "aBoolean" -> Vector("true", "false", "invalidBoolean"))

    val typeMappings = ComplexObject(MP3ObjectType, Map(
      new URI("http://myInt") -> SimpleObject(Vector("anInt"), dataType = DataType.Integer),
      new URI("http://myDecimal") -> SimpleObject(Vector("aDecimal"), dataType = DataType.Decimal),
      new URI("http://myBool") -> SimpleObject(Vector("aBoolean"), dataType = DataType.Bool)
    ))
    val result = new MetadataMapper(GenericFileDataMap, Map(MP3ContentType -> typeMappings)).convertMetadataToRDF(
      URNWithTestUUID, Some(MP3ContentType), metadata, None, HashOfTestMP3, TestMP3FileName.toString)

    val expectedOutputBuilder = new ModelBuilder().subject(URNWithTestUUID).
      add(RDF.TYPE, asIRI(MP3ObjectType)).
      add(asIRI(FileNamePredicate), TestMP3FileName.toString).
      add(asIRI(FileFormatPredicate), MP3ContentType).
      add(asIRI(DataLocationPredicate), HashOfTestMP3)
    Vector(654, 143).foreach((intValue: Int) => expectedOutputBuilder.add(iri("http://myInt"), valueFactory.createLiteral(intValue)))
    Vector(BigDecimal(2.71828), BigDecimal(42.001)).foreach((decimalValue: BigDecimal) =>
      expectedOutputBuilder.add(iri("http://myDecimal"), valueFactory.createLiteral(decimalValue.bigDecimal)))
    Vector(true, false).foreach((boolValue: Boolean) =>
      expectedOutputBuilder.add(iri("http://myBool"), valueFactory.createLiteral(boolValue)))

    result should beIsomorphicWith(expectedOutputBuilder.build())
  }

  it should "create RDF Blank node when mapping is to ComplexObject" in {
    val metadata = Map("xmpDM:artist" -> Vector("Bob Dylan"))
    val mapper = new MetadataMapper(GenericFileDataMap, Map(MP3ContentType ->
      ComplexObject(MP3ObjectType, Map(
        AuthorPredicate -> ComplexObject(PersonObjectType, Map(NamePredicate -> SimpleObject(Vector("xmpDM:artist"), multiplicityStrategy = Split)))
      )))
    )
    val result = mapper.convertMetadataToRDF(URNWithTestUUID, Some(MP3ContentType), metadata, None, HashOfTestMP3, TestMP3FileName.toString)
    result.size() should === (7)

    val actualAuthorObject = Models.objectResource(result.filter(iri(URNWithTestUUID), asIRI(AuthorPredicate), null)).get
    val expectedAuthor = new ModelBuilder().subject(bnode()).
      add(RDF.TYPE, asIRI(PersonObjectType)).
      add(asIRI(NamePredicate), literal("Bob Dylan")).build()
    result.filter(actualAuthorObject, null, null) should beIsomorphicWith(expectedAuthor)
  }

  it should "not create an empty child node" in {
    val metadata = Map.empty[String, Vector[String]]
    val mapper = new MetadataMapper(GenericFileDataMap, Map(MP3ContentType ->
      ComplexObject(MP3ObjectType, Map(
        AuthorPredicate -> ComplexObject(PersonObjectType, Map(NamePredicate -> SimpleObject(Vector("xmpDM:artist"), multiplicityStrategy = Aggregate)))
      )))
    )
    val result = mapper.convertMetadataToRDF(URNWithTestUUID, Some(MP3ContentType), metadata, None, HashOfTestMP3, TestMP3FileName.toString)
    result.size() should ===(4)

    result.filter(iri(URNWithTestUUID), asIRI(AuthorPredicate), null).isEmpty should ===(true)
  }

  it should "map dates" in {
    val dates = Vector("2010-08-08T15:35:33Z", "2011-08-08T10:35:33Z")
    val metadata = Map("Creation-Date" -> dates)

    val result = new MetadataMapper(GenericFileDataMap, Map(
      MP3ContentType -> ComplexObject(MP3ObjectType, Map(DateCreatedPredicate -> SimpleObject(Vector("Creation-Date"), DataType.DateTime))))
    ).convertMetadataToRDF(URNWithTestUUID, Some(MP3ContentType), metadata, None, HashOfTestMP3, TestMP3FileName.toString)

    assertDateObjects(dates.map(s => s.substring(0, s.length-1) + ".000Z"), result, URNWithTestUUIDAsURI, DateCreatedPredicate)
  }

  it should "map GPS coordinates to GeoSPARQL compatible values" in {
    val metadata = Map("GPS Longitude" -> Vector("-87.0° 37.0' 28.17839999999478\""), "GPS Latitude" -> Vector("41.0° 53.0' 20.21279999999706\""))

    val result = new MetadataMapper(GenericFileDataMap, Map("thing/place" ->
      ComplexObject(PlaceObjectType,
        Map(GeoCoordinatesPredicateUri -> SimpleObject(Vector("GPS Latitude", "GPS Longitude"), DataType.Geo)))
    )).convertMetadataToRDF(URNWithTestUUID, Some("thing/place"), metadata, None, HashOfTestMP3, TestMP3FileName.toString)

    val expectedResult = new LinkedHashModel()
    expectedResult.add(iri(URNWithTestUUID), RDF.TYPE, asIRI(PlaceObjectType))
    expectedResult.add(iri(URNWithTestUUID), GeoCoordinatesPredicateIri,
      literal("POINT(-87.62449 41.88895)", GeoCoordinatesLiteralType))

    expectedResult.asScala.forall(result.contains) should === (true)
  }

  it should "ignore fields in the mapping that are missing from the data" in {
    val metadata = Map("name" -> Vector("Bob Dylan"))
    val map = ComplexObject(MP3ObjectType, Map(
      NamePredicate -> SimpleObject(Vector("name")),
      GenrePredicate -> SimpleObject(Vector("genre")),
      DateCreatedPredicate -> SimpleObject(Vector("Creation-Date"), DataType.DateTime),
      GeoCoordinatesPredicateUri -> SimpleObject(Vector("GEO Lat", "GEO Long"), DataType.Geo)
    ))
    val result = new MetadataMapper(GenericFileDataMap, Map(MP3ContentType -> map)).convertMetadataToRDF(
      URNWithTestUUID, Some(MP3ContentType), metadata, None, HashOfTestMP3, TestMP3FileName.toString)

    result.size() should === (5)
    assertSingleStringObject("Bob Dylan", result, URNWithTestUUIDAsURI, NamePredicate)
  }

  it should "handle multiple string values differently depending on multiplicityStrategy" in {
    def mapData(metadata: Map[String, Vector[String]], multiplicityStrategy: MultiplicityStrategy) = {
      val mapper = new MetadataMapper(GenericFileDataMap, Map(MP3ContentType ->
        ComplexObject(MP3ObjectType, Map(
          AuthorPredicate -> ComplexObject(PersonObjectType, Map(
            NamePredicate -> SimpleObject(Vector("xmpDM:artist"), multiplicityStrategy = multiplicityStrategy)))
        )))
      )
      mapper.convertMetadataToRDF(URNWithTestUUID, Some(MP3ContentType), metadata, None, HashOfTestMP3, TestMP3FileName.toString)
    }

    val authorNames = Vector("Bob Dylan", "Robert Zimmerman")
    val resultWithAggregate = mapData(Map("xmpDM:artist" -> authorNames), Aggregate)
    val actualAggregateAuthors = Models.objectResources(resultWithAggregate.filter(iri(URNWithTestUUID), asIRI(AuthorPredicate), null)).asScala.toVector
    actualAggregateAuthors.length should === (1)
    actualAggregateAuthors.foreach(actualAggregateAuthorObject => {
      val expectedAuthor = new ModelBuilder().subject(bnode()).add(RDF.TYPE, asIRI(PersonObjectType))

      val expectedAuthorComplete = authorNames.foldLeft(expectedAuthor)((inProgres, authorName) => {
        inProgres.add(asIRI(NamePredicate), literal(authorName))
      }).build()

      resultWithAggregate.filter(actualAggregateAuthorObject, null, null) should beIsomorphicWith(expectedAuthorComplete)
    })

    val resultWithSplit = mapData(Map("xmpDM:artist" -> authorNames), Split)

    authorNames.foreach(name => {
      val authorSubject = Models.subject(resultWithSplit.filter(null, asIRI(NamePredicate), literal(name))).get()
      val expectedAuthor = new ModelBuilder().subject(bnode()).
        add(RDF.TYPE, asIRI(PersonObjectType)).
        add(asIRI(NamePredicate), literal(name)).build()
      resultWithSplit.filter(authorSubject, null, null) should beIsomorphicWith(expectedAuthor)
    })

    val resultWithNoInputData = mapData(Map.empty, Split)
    val expectedResultWithNoInputData = new ModelBuilder().subject(URNWithTestUUID).
      add(RDF.TYPE, iri(MP3ObjectType.toString)).
      add(iri(FileFormatPredicate.toString), MP3ContentType).
      add(iri(FileNamePredicate.toString), TestMP3FileName.toString).
      add(iri(DataLocationPredicate.toString), HashOfTestMP3).build()
    resultWithNoInputData should === (expectedResultWithNoInputData)
  }

  it should "handle multiple GPS coordinate values differently depending on multiplicityStrategy" in {
    def mapData(metadata: Map[String, Vector[String]], multiplicityStrategy: MultiplicityStrategy) = {
      val mapper = new MetadataMapper(GenericFileDataMap, Map(ICalContentType ->
        ComplexObject(EventObjectType,
          Map(LocationPredicate -> ComplexObject(PlaceObjectType,
            Map(GeoCoordinatesPredicateUri ->
              SimpleObject(Vector("GPS Latitude", "GPS Longitude"), DataType.Geo, multiplicityStrategy)))))
      ))
      mapper.convertMetadataToRDF(URNWithTestUUID, Some(ICalContentType), metadata, None, HashOfTestMP3, TestMP3FileName.toString)
    }

    val metadata = Map(
      "GPS Longitude" -> Vector("-87.0° 37.0' 28.17839999999478\"", "-80° 36' 17.17\""),
      "GPS Latitude" -> Vector("41.0° 53.0' 20.21279999999706\"", "28° 24' 21.02\""))

    val expectedPoints = Vector("POINT(-87.62449 41.88895)", "POINT(-80.60477 28.40584)")

    val resultWithAggregate = mapData(metadata, Aggregate)
    val actualAggregateLocations = Models.objectResources(resultWithAggregate.filter(iri(URNWithTestUUID), asIRI(LocationPredicate), null)).asScala.toVector
    actualAggregateLocations.length should ===(1)
    actualAggregateLocations.foreach(actualAggregateLocationObject => {
      val expectedLocation = new ModelBuilder().subject(bnode()).add(RDF.TYPE, asIRI(PlaceObjectType))

      val expectedLocationComplete = expectedPoints.foldLeft(expectedLocation)((inProgres, location) => {
        inProgres.add(GeoCoordinatesPredicateIri, literal(location, GeoCoordinatesLiteralType))
      }).build()

      resultWithAggregate.filter(actualAggregateLocationObject, null, null) should beIsomorphicWith(expectedLocationComplete)
    })

    val resultWithSplit = mapData(metadata, Split)
    expectedPoints.foreach(location => {
      val locationSubject = Models.subject(resultWithSplit.filter(null,
        GeoCoordinatesPredicateIri,
        literal(location, GeoCoordinatesLiteralType))).get()
      val expectedLocation = new ModelBuilder().subject(bnode()).
        add(RDF.TYPE, asIRI(PlaceObjectType)).
        add(GeoCoordinatesPredicateIri,
          literal(location, GeoCoordinatesLiteralType)).build()
      resultWithSplit.filter(locationSubject, null, null) should beIsomorphicWith(expectedLocation)
    })
  }

  it should "default to octet-stream content type if no file format is supplied" in {
    val fakeHash = "asdfqwer"
    val fileName = "unknonwnFile"
    val result = new MetadataMapper(GenericFileDataMap, Map()).convertMetadataToRDF(URNWithTestUUID, None, Map(), None, fakeHash, fileName)
    result.size() should ===(4)
    assertSingleStringObject(fakeHash, result, URNWithTestUUIDAsURI, DataLocationPredicate)
    assertSingleStringObject(fileName, result, URNWithTestUUIDAsURI, FileNamePredicate)
    assertSingleURIObject(GenericMediaFileType, result, URNWithTestUUIDAsURI, new URI(RDF.TYPE.toString))
    assertSingleStringObject(OctetStreamContentType, result, URNWithTestUUIDAsURI, FileFormatPredicate)
  }

  private def assertSingleStringObject(expectedObjectValue: String, model: Model, subject: URI, predicate: URI): Unit = {
    val filtered: Model = model.filter(iri(subject.toString), iri(predicate.toString), null)
    filtered.size() should === (1)
    Models.`object`(filtered) should === (Optional.of[Value](literal(expectedObjectValue)))
  }

  private def assertSingleURIObject(expectedObjectURI: URI, model: Model, subject: URI, predicate: URI): Unit = {
    val filtered: Model = model.filter(iri(subject.toString), iri(predicate.toString), null)
    filtered.size() should === (1)
    Models.`object`(filtered) should === (Optional.of[Value](iri(expectedObjectURI.toString)))
  }

  private def assertDateObjects(expectedDates: Vector[String], model: Model, subject: URI, predicate: URI): Unit = {
    val filtered: Model = model.filter(iri(subject.toString), iri(predicate.toString), null)
    filtered.size() should === (expectedDates.size)
    Models.objectLiterals(filtered).asScala should ===(expectedDates.map(date => literal(date, getDateDataType())).toSet)
  }

  private def getDateDataType() =
    XMLDatatypeUtil.qnameToCoreDatatype(DatatypeFactory.newInstance.newXMLGregorianCalendar(new GregorianCalendar).getXMLSchemaType)
}
