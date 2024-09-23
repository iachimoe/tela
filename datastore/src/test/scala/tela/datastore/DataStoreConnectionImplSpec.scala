package tela.datastore

import org.eclipse.rdf4j.model.{Model, Resource}

import java.net.URI
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.UUID
import org.eclipse.rdf4j.model.impl.{LinkedHashModel, SimpleValueFactory}
import org.eclipse.rdf4j.model.util.Values.{bnode, iri, literal}
import org.eclipse.rdf4j.model.util.{ModelBuilder, Models}
import org.eclipse.rdf4j.model.vocabulary.{GEO, GEOF, RDF, XSD}
import org.eclipse.rdf4j.rio.RDFFormat
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.mockito.{ArgumentMatcher, ArgumentMatchers}
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatest.matchers.should.Matchers.*
import tela.baseinterfaces.{ComplexObject, XMPPSession}

import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneId}
import scala.xml.*
import scala.concurrent.ExecutionContext.global
import scala.concurrent.{Await, Future}

class DataStoreConnectionImplSpec extends DataStoreBaseSpec {
  private val BaseTestDir = TestDataRoot.resolve("store")
  private val NonExistentDataStore = TestDataRoot.resolve("nonExistent")

  private val TestMediaItemsRoot = BaseTestDir.resolve(TestUsername).resolve(DataStoreConnectionImpl.MediaItemsFolderName)

  private val AdditionalTestUUID1 = UUID.fromString("5233899b-ba7e-504f-bb83-ceebac62decf")
  private val AdditionalTestUUID2 = UUID.fromString("3bda1540-d089-5a1a-8f0d-94eba8068e58")
  private val AdditionalTestUUID3 = UUID.fromString("e4d2c732-bbc1-5ef4-869f-5007ceb55f6e")

  // This matches the format of dates that RDF4J produces
  private val TestDateWithIsoInstantFormat = TestDateAsLocalDateTime.atZone(ZoneId.of("UTC")).format(
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX"))

  private val TestProfileInfoAsJSON = s"""[
                                  |    {
                                  |        "@id": "$TestDataObjectUri",
                                  |        "@type": [ "http://xmlns.com/foaf/0.1/Person" ],
                                  |        "http://xmlns.com/foaf/0.1/familyName": [
                                  |            {
                                  |                "@value": "Foo"
                                  |            }
                                  |        ],
                                  |        "http://xmlns.com/foaf/0.1/firstName": [
                                  |            {
                                  |                "@value": "Walks"
                                  |            }
                                  |        ]
                                  |    }
                                  |]""".stripMargin

  private val TestFamilyNameFromProfileInfo =
    s"""[
       |    {
       |        "@id": "$TestDataObjectUri",
       |        "http://xmlns.com/foaf/0.1/familyName": [
       |            {
       |                "@value": "Foo"
       |            }
       |        ]
       |    }
       |]""".stripMargin

  // Note that the HTML tags are stripped out of the text content
  private val TestHTMLFileMetadata = new ModelBuilder().subject(asIRI(URNWithTestUUIDAsURI)).
    add(RDF.TYPE, asIRI(GenericMediaFileType)).
    add(asIRI(HashPredicate), HashOfTestHtmlFile).
    add(asIRI(TextContentPredicate), fileContentLikeFromTika(11, "Hello")).
    add(asIRI(FileNamePredicate), TestHtmlFileName.toString).
    add(asIRI(FileFormatPredicate), s"$TextHtmlContentType; charset=ISO-8859-1").build()

  private val TestICalFileMetadata = new ModelBuilder().subject(asIRI(URNWithTestUUIDAsURI)).
    add(RDF.TYPE, asIRI(GenericMediaFileType)).
    add(asIRI(HashPredicate), HashOfTestIcalFile).
    add(asIRI(FileNamePredicate), TestIcalWithEventFileName.toString).
    add(asIRI(FileFormatPredicate), ICalContentType).
    add(iri("http://schema.org/name"), "DDD London #3 - Strategic and Collaborative Domain-Driven Design").build()

  private def testMP3FileMetadata(id: UUID, hash: String, prefixedNewlines: Int, lastModified: String) = {
    val authorNodeSubject = bnode()
    val authorNode = new ModelBuilder().subject(authorNodeSubject).
      add(RDF.TYPE, iri("http://schema.org/Person")).
      add(iri("http://schema.org/name"), "tela").build()

    new ModelBuilder(authorNode).subject(s"${DataStoreConnectionImpl.URNBaseForUUIDs}$id").
      add(RDF.TYPE, asIRI(MP3ObjectType)).
      add(asIRI(HashPredicate), hash).
      add(asIRI(FileNamePredicate), TestMP3FileName.toString).
      add(asIRI(FileFormatPredicate), MP3ContentType).
      add(iri("http://schema.org/author"), authorNodeSubject).
      add(iri("http://schema.org/genre"), "Rock").
      add(iri("http://schema.org/name"), "Short, Silent MP3").
      add(iri("http://schema.org/lastModified"), literal(lastModified, XSD.DATETIME)).
      add(asIRI(TextContentPredicate), fileContentLikeFromTika(prefixedNewlines,
        "Short, Silent MP3\n\nShort, Silent MP3\ntela\nRock\n0.15673469\nXXX - \nSmall MP3 for testing tela")).build()
  }

  private def testTextFileMetadata(id: UUID, hash: String, prefixedNewlines: Int, lastModified: String) =
    new ModelBuilder().subject(s"${DataStoreConnectionImpl.URNBaseForUUIDs}$id").
      add(RDF.TYPE, asIRI(GenericMediaFileType)).
      add(asIRI(HashPredicate), hash).
      add(asIRI(FileNamePredicate), TestTextFileName.toString).
      add(asIRI(TextContentPredicate), fileContentLikeFromTika(prefixedNewlines, fileContent(TestTextFile))).
      add(asIRI(FileFormatPredicate), s"$PlainTextContentType; charset=ISO-8859-1").
      add(iri("http://schema.org/lastModified"), literal(lastModified, XSD.DATETIME)).
      add(iri("http://schema.org/name"), TestTextFileName).build()

  private val TestZipFileMetadata = new ModelBuilder().subject(asIRI(URNWithTestUUIDAsURI)).
    add(RDF.TYPE, asIRI(GenericMediaFileType)).
    add(asIRI(HashPredicate), HashOfTestZipFile).
    add(asIRI(FileNamePredicate), TestZipFileName.toString).
    add(asIRI(FileFormatPredicate), ZipFileContentType).
    add(asIRI(TextContentPredicate),
      fileContentLikeFromTika(10, s"music/$TestMP3FileName") + fileContentLikeFromTika(2, "testTextFile.zip") + "\n"
    ).build()

  private val TestInnerZipFileMetadata = new ModelBuilder().subject(s"${DataStoreConnectionImpl.URNBaseForUUIDs}$AdditionalTestUUID3").
    add(RDF.TYPE, asIRI(GenericMediaFileType)).
    add(asIRI(HashPredicate), s"$HashOfTestZipFile/testTextFile.zip").
    add(asIRI(FileNamePredicate), "testTextFile.zip").
    add(asIRI(FileFormatPredicate), ZipFileContentType).
    add(asIRI(TextContentPredicate), fileContentLikeFromTika(17, s"$TestTextFileName\n")).
    add(iri("http://schema.org/lastModified"), literal("2022-07-22T12:57:01.000Z", XSD.DATETIME)).build()

  private val TestProfileInfoAsXML = <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about={TestDataObjectUri.toString}>
      <rdf:type rdf:resource="http://xmlns.com/foaf/0.1/Person"/>
      <familyName xmlns="http://xmlns.com/foaf/0.1/">Foo</familyName>
      <firstName xmlns="http://xmlns.com/foaf/0.1/">Walks</firstName>
    </rdf:Description>
  </rdf:RDF>

  private class TestEnvironment(val connection: DataStoreConnectionImpl, val xmppSession: XMPPSession)

  private def testEnvironment(runTest: TestEnvironment => Unit): Unit = {
    recursiveDelete(BaseTestDir.resolve(TestUsername))
    recursiveDelete(NonExistentDataStore)

    val xmppSession = mock[XMPPSession]

    var uuids = Vector(TestUUID, AdditionalTestUUID1, AdditionalTestUUID2, AdditionalTestUUID3)
    val connection = Await.result(DataStoreConnectionImpl.getDataStore(BaseTestDir, TestUsername, GenericFileDataMap,
      Map(MP3ContentType -> MP3FileDataMap, ICalContentType -> ICalDataMap, PlainTextContentType -> PlainTextDataMap),
      xmppSession, TestTikaConfigFile, () => {
        val result = uuids.head
        uuids = uuids.tail
        result
      }, global), TestAwaitTimeout)

    try {
      runTest(new TestEnvironment(connection, xmppSession))
    } finally {
      cleanUpDataStore(connection)
    }
  }

  private def cleanUpDataStore(connection: DataStoreConnectionImpl): Unit = {
    Await.result(connection.closeConnection(), TestAwaitTimeout)
    connection.connection.isOpen should === (false)
    connection.repository.isInitialized should === (false)
  }

  "getDataStore" should "throw an IllegalArgumentException for a non-existent data store" in testEnvironment { environment =>
    assertThrows[IllegalArgumentException] {
      Await.result(DataStoreConnectionImpl.getDataStore(
        NonExistentDataStore, TestUsername, ComplexObject(new URI(""), Map()), Map(),
        environment.xmppSession, TestTikaConfigFile, () => UUID.randomUUID(), global), TestAwaitTimeout)
    }
  }

  "retrieveJson" should "return an empty array when an arbitrary URI is requested from an empty store" in testEnvironment { environment =>
    environment.connection.retrieveJSON(TestDataObjectUri) should beFutureJSONLDEquivalentTo("[]")
  }

  it should "retrieve the same JSONLD graph that was inserted when the URI of that graph is requested" in testEnvironment { environment =>
    insertJSON(TestProfileInfoAsJSON, environment)
    environment.connection.retrieveJSON(TestDataObjectUri) should beFutureJSONLDEquivalentTo(TestProfileInfoAsJSON)
    environment.connection.retrieveJSON(new URI("http://tela/nonExistant")) should beFutureJSONLDEquivalentTo("[]")
  }

  it should "not retrieve unrelated data" in testEnvironment { environment =>
    val otherPerson = s"""[
                         |    {
                         |        "@id": "uri:Other",
                         |        "@type": [ "http://xmlns.com/foaf/0.1/Person" ]
                         |    }
                         |]""".stripMargin

    insertJSON(TestProfileInfoAsJSON, environment)
    insertJSON(otherPerson, environment)
    environment.connection.retrieveJSON(TestDataObjectUri) should beFutureJSONLDEquivalentTo(TestProfileInfoAsJSON)
  }

  "insertJson" should "overwrite old content when new content is inserted with a pre-existing URI" in testEnvironment { environment =>
    val alternateData = s"""[
                           |    {
                           |        "@id": "${TestDataObjectUri}",
                           |        "@type": [ "http://xmlns.com/foaf/0.1/Person" ],
                           |        "http://xmlns.com/foaf/0.1/familyName": [
                           |            {
                           |                "@value": "asf"
                           |            }
                           |        ],
                           |        "http://xmlns.com/foaf/0.1/firstName": [
                           |            {
                           |                "@value": "qwer"
                           |            }
                           |        ]
                           |    }
                           |]""".stripMargin

    insertJSON(alternateData, environment)
    insertJSON(TestProfileInfoAsJSON, environment)
    environment.connection.retrieveJSON(TestDataObjectUri) should beFutureJSONLDEquivalentTo(TestProfileInfoAsJSON)
  }

  "publish" should "publish given URI in XML format via XMPP" in testEnvironment { environment =>
    insertJSON(TestProfileInfoAsJSON, environment)
    environment.connection.publish(TestDataObjectUri)

    verify(environment.xmppSession).publish(ArgumentMatchers.eq(TestDataObjectUri), argThat(new XMLMatcher(TestProfileInfoAsXML)))
  }

  private def insertJSON(json: String, environment: TestEnvironment): Unit = {
    Await.result(environment.connection.insertJSON(json), TestAwaitTimeout)
  }

  "getPublishedData" should "retrieve published data from XMPPSession and return response as JSON" in testEnvironment { environment =>
    when(environment.xmppSession.getPublishedData(TestUsername, TestDataObjectUri)).thenReturn(Future.successful(TestProfileInfoAsXML.toString))

    environment.connection.retrievePublishedDataAsJSON(TestUsername, TestDataObjectUri) should beFutureJSONLDEquivalentTo(TestProfileInfoAsJSON)
  }

  "storeMediaItem" should "place contents of temporary file in data store with correct hash and remove temporary file" in testEnvironment { environment =>
    val tempFile = createTempFileAndStoreContent(TestHtmlFile, TestHtmlFileName, None, environment.connection)
    TestMediaItemsRoot.resolve(HashOfTestHtmlFile).toFile.exists() should === (true)
    Files.exists(tempFile) should === (false)
  }

  it should "be able to store multiple files" in testEnvironment { environment =>
    createTempFileAndStoreContent(TestHtmlFile, TestHtmlFileName, None, environment.connection)
    createTempFileAndStoreContent(TestMP3, TestMP3FileName, None, environment.connection)
    createTempFileAndStoreContent(TestMP3, TestMP3FileName, None, environment.connection) //verifying that storing the same file twice won't cause an exception

    TestMediaItemsRoot.resolve(HashOfTestHtmlFile).toFile.exists() should === (true)
    TestMediaItemsRoot.resolve(HashOfTestMP3).toFile.exists() should === (true)
  }

  it should "store UUID, hash and file format of media items in RDF store" in testEnvironment { environment =>
    createTempFileAndStoreContent(TestHtmlFile, TestHtmlFileName, None, environment.connection)
    environment.connection.retrieveJSON(URNWithTestUUIDAsURI) should beFutureJSONLDEquivalentTo(TestHTMLFileMetadata)
  }

  it should "store metadata from MP3 file in RDF store" in testEnvironment { environment =>
    createTempFileAndStoreContent(TestMP3, TestMP3FileName, Some(TestDateAsLocalDateTime), environment.connection)
    environment.connection.retrieveJSON(URNWithTestUUIDAsURI) should beFutureJSONLDEquivalentTo(
      testMP3FileMetadata(TestUUID, HashOfTestMP3, 22, TestDateWithIsoInstantFormat))
  }

  it should "extract appropriate metadata from ical content" in testEnvironment { environment =>
    createTempFileAndStoreContent(TestIcalFileWithEvent, TestIcalWithEventFileName, None, environment.connection)
    environment.connection.retrieveJSON(URNWithTestUUIDAsURI) should beFutureJSONLDEquivalentTo(TestICalFileMetadata)
  }

  it should "store filename and last modified date" in testEnvironment { environment =>
    createTempFileAndStoreContent(TestTextFile, TestTextFileName, Some(TestDateAsLocalDateTime), environment.connection)
    environment.connection.retrieveJSON(URNWithTestUUIDAsURI) should beFutureJSONLDEquivalentTo(
      testTextFileMetadata(TestUUID, HashOfTestTextFile, 11, TestDateWithIsoInstantFormat))
  }

  it should "extract metadata for all contents in a compound file format and index text" in testEnvironment { environment =>
    createTempFileAndStoreContent(TestZipFile, TestZipFileName, None, environment.connection)
    environment.connection.retrieveJSON(URNWithTestUUIDAsURI) should beFutureJSONLDEquivalentTo(TestZipFileMetadata)
    environment.connection.retrieveJSON(urnFromUuid(AdditionalTestUUID1)) should beFutureJSONLDEquivalentTo(
      testMP3FileMetadata(AdditionalTestUUID1, s"$HashOfTestZipFile/music/$TestMP3FileName", 28, "2022-07-20T08:22:00.000Z"))
    environment.connection.retrieveJSON(urnFromUuid(AdditionalTestUUID2)) should beFutureJSONLDEquivalentTo(
      testTextFileMetadata(AdditionalTestUUID2, s"$HashOfTestZipFile/testTextFile.zip/$TestTextFileName", 17, "2022-07-20T08:22:00.000Z"))
    environment.connection.retrieveJSON(urnFromUuid(AdditionalTestUUID3)) should beFutureJSONLDEquivalentTo(TestInnerZipFileMetadata)
  }

  "retrieveMediaItem" should "return the absolute path of the requested file if it exists in the data store" in testEnvironment { environment =>
    createTempFileAndStoreContent(TestHtmlFile, TestHtmlFileName, None, environment.connection)
    retrieveMediaItem(HashOfTestHtmlFile, environment) should === (Some(TestMediaItemsRoot.resolve(HashOfTestHtmlFile)))
  }

  it should "return None for a non-existent media item" in testEnvironment { environment =>
    createTempFileAndStoreContent(TestHtmlFile, TestHtmlFileName, None, environment.connection) //adding this file to ensure that data store exists
    retrieveMediaItem("notARealHash", environment) should === (None)
  }

  it should "prohibit attempts to retrieve files from other folders" in testEnvironment { environment =>
    createTempFileAndStoreContent(TestHtmlFile, TestHtmlFileName, None, environment.connection) //adding this file to ensure that data store exists
    retrieveMediaItem("../../.gitignore", environment) should === (None)
  }

  private def retrieveMediaItem(hash: String, environment: TestEnvironment): Option[Path] = {
    Await.result(environment.connection.retrieveMediaItem(hash), TestAwaitTimeout)
  }

  "runSPARQLQuery" should "return empty array for query against empty repository" in testEnvironment { environment =>
    environment.connection.runSPARQLQuery(WildcardSparqlQuery) should beFutureJSONLDEquivalentTo("[]")
  }

  it should "handle full text search queries" in testEnvironment { environment =>
    insertJSON(TestProfileInfoAsJSON, environment)

    // We search for "walking" even though the name is "Walks" to verify that stemming works for both content and queries
    environment.connection.runSPARQLQuery("PREFIX search: <http://www.openrdf.org/contrib/lucenesail#>\n" +
      "PREFIX foaf: <http://xmlns.com/foaf/0.1/>\n" +
      "CONSTRUCT { } WHERE { ?s foaf:familyName ?o . ?s search:matches [ search:query \"walking\" ] }") should beFutureJSONLDEquivalentTo(TestFamilyNameFromProfileInfo)
  }

  it should "handle GeoSparql queries" in testEnvironment { environment =>
    //At the time of writing the LuceneSail is the only Sail that supports GeoSparql

    val valueFactory = SimpleValueFactory.getInstance()

    val graphWithRestaurant = new LinkedHashModel()
    graphWithRestaurant.add(valueFactory.createIRI("http://leVinCoeur"), GEO.AS_WKT, valueFactory.createLiteral("POINT (2.29397 48.87510)", GEO.WKT_LITERAL))

    val graphWithRestaurantAsJson: String = DataStoreConnectionImpl.convertRDFModelToJson(graphWithRestaurant)
    insertJSON(graphWithRestaurantAsJson, environment)

    val placesNearArcDeTriompheQuery =
      "prefix geo: <" + GEO.NAMESPACE + ">" +
        "prefix geof: <" + GEOF.NAMESPACE + ">" +
        "prefix uom: <" + GEOF.UOM_NAMESPACE + ">" +
        "prefix xsd: <" + XSD.NAMESPACE + ">" +
        "DESCRIBE ?subject where { ?subject geo:asWKT ?object . filter(geof:distance(\"POINT (2.2950 48.8738)\"^^geo:wktLiteral, ?object, uom:metre) < \"500.0\"^^xsd:double) }"

    val result = environment.connection.runSPARQLQuery(placesNearArcDeTriompheQuery)
    result should beFutureJSONLDEquivalentTo(graphWithRestaurantAsJson)
  }

  private def createTempFileAndStoreContent(path: Path,
                                            originalFileName: Path,
                                            lastModified: Option[LocalDateTime],
                                            connection: DataStoreConnectionImpl): Path = {
    val tempFile = Files.createTempFile("aaa", "")
    Files.copy(path, tempFile, StandardCopyOption.REPLACE_EXISTING)
    Await.result(connection.storeMediaItem(tempFile, originalFileName, lastModified), TestAwaitTimeout)
    tempFile
  }

  private def beFutureJSONLDEquivalentTo(expectedValue: Model): Matcher[Future[String]] = {
    new Matcher[Future[String]]() {
      override def apply(left: Future[String]): MatchResult = {
        val actualValueAsModel = getJSONAsRDFModel(Await.result(left, TestAwaitTimeout))
        MatchResult(
          Models.isomorphic(expectedValue, actualValueAsModel),
          s"""Expected $expectedValue, but got $actualValueAsModel""",
          s"""Got the expected value $expectedValue""")
      }
    }
  }

  private def beFutureJSONLDEquivalentTo(expectedValue: String): Matcher[Future[String]] = {
    beFutureJSONLDEquivalentTo(getJSONAsRDFModel(expectedValue))
  }

  private def getJSONAsRDFModel(json: String): LinkedHashModel = {
    DataStoreConnectionImpl.convertDataToRDFModel(json, RDFFormat.JSONLD)
  }

  private def recursiveDelete(file: Path): Unit = {
    if (Files.isDirectory(file))
      Files.list(file).forEach(child => recursiveDelete(child))
    Files.deleteIfExists(file)
  }

  //TODO Sadly tika is a little funny putting these newlines all over the place
  //Probably the best thing to do going forward is to just strip out leading/trailing newlines
  //in the production code before indexing the text, but let's wait and see how tika evolves in future versions
  private def fileContentLikeFromTika(prefixedNewlines: Int, content: String) =
    "\n".repeat(prefixedNewlines) + content + "\n"

  private def fileContent(file: Path) = {
    new String(Files.readAllBytes(file))
  }

  private class XMLMatcher(private val expected: Elem) extends ArgumentMatcher[String] {
    override def matches(actual: String): Boolean = {
      //Ensure that the result doesn't start with a declaration so that we can easily embed it in another XML doc
      !actual.startsWith("<?xml") && Utility.trim(expected) == Utility.trim(XML.loadString(actual))
    }
  }
}
