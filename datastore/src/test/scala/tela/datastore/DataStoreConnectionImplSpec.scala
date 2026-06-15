package tela.datastore

import org.eclipse.rdf4j.model.{BNode, Model, Resource}

import java.net.URI
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.UUID
import org.eclipse.rdf4j.model.impl.{LinkedHashModel, SimpleValueFactory}
import org.eclipse.rdf4j.model.util.Values.{bnode, literal}
import org.eclipse.rdf4j.model.util.ModelBuilder
import org.eclipse.rdf4j.model.vocabulary.{GEO, GEOF, RDF, XSD}
import org.eclipse.rdf4j.rio.RDFFormat
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.mockito.{ArgumentMatcher, ArgumentMatchers}
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatest.matchers.should.Matchers.*
import tela.baseinterfaces.{ComplexObject, XMPPSession}
import tela.datastore.MetadataMapper.GeoCoordinatesLiteralType

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
  private val AdditionalTestUUID1AsURN = urnFromUuid(AdditionalTestUUID1)
  private val AdditionalTestUUID2 = UUID.fromString("3bda1540-d089-5a1a-8f0d-94eba8068e58")
  private val AdditionalTestUUID2AsURN = urnFromUuid(AdditionalTestUUID2)
  private val AdditionalTestUUID3 = UUID.fromString("e4d2c732-bbc1-5ef4-869f-5007ceb55f6e")
  private val AdditionalTestUUID3AsURN = urnFromUuid(AdditionalTestUUID3)
  private val AdditionalTestUUID4 = UUID.fromString("f81d4fae-7dec-11d0-a765-00a0c91e6bf6")
  private val AdditionalTestUUID4AsURN = urnFromUuid(AdditionalTestUUID4)
  private val AdditionalTestUUID5 = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
  private val AdditionalTestUUID5AsURN = urnFromUuid(AdditionalTestUUID5)

  // This matches the format of dates that RDF4J produces
  private val TestDateWithIsoInstantFormat = TestDateAsLocalDateTime.atZone(ZoneId.of("UTC")).format(
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX"))

  private def testProfileInfoAsJSON(uri: URI) = s"""[
                                  |    {
                                  |        "@id": "$uri",
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

  private def testFamilyNameFromProfileInfo(uri: URI) =
    s"""[
       |    {
       |        "@id": "$uri",
       |        "http://xmlns.com/foaf/0.1/familyName": [
       |            {
       |                "@value": "Foo"
       |            }
       |        ]
       |    }
       |]""".stripMargin

  private def createRDFModel_multipleValues(subject: URI, subjectType: URI, properties: Map[URI, Vector[Object]], maybeInitial: Option[Model]) = {
    val builder = maybeInitial.map(initial => new ModelBuilder(initial)).getOrElse(new ModelBuilder())
    builder.subject(asIRI(subject)).add(RDF.TYPE, asIRI(subjectType))
    properties.foreach {
      case (predicate, objectValues) => objectValues.foreach(o => builder.add(asIRI(predicate), o))
    }
    builder.build()
  }

  private def createRDFModel(subject: URI, subjectType: URI, properties: Map[URI, Object], maybeInitial: Option[Model] = None) = {
    createRDFModel_multipleValues(subject, subjectType, properties.view.mapValues(o => Vector(o)).toMap, maybeInitial)
  }

  // Note that the HTML tags are stripped out of the text content
  private def testHTMLFileMetadata(uri: URI) =
    createRDFModel(uri, GenericMediaFileType, Map(
      DataLocationPredicate -> HashOfTestHtmlFile,
      TextContentPredicate -> fileContentLikeFromTika(13, "Hello", 1),
      FileNamePredicate -> TestHtmlFileName.toString,
      FileFormatPredicate -> s"$TextHtmlContentType; charset=ISO-8859-1"))

  private def testICalFileMetadata(uri: URI) =
    createRDFModel(uri, GenericMediaFileType, Map(
      DataLocationPredicate -> HashOfTestIcalFile,
      FileNamePredicate -> TestIcalWithEventFileName.toString,
      FileFormatPredicate -> ICalContentType,
      NamePredicate -> "DDD London #3 - Strategic and Collaborative Domain-Driven Design"))

  private def testEmailAttachment(uri: URI) =
    createRDFModel(uri, GenericMediaFileType, Map(
      DataLocationPredicate -> (HashOfTestEmailFile + "/a.txt"), //TODO This path is wrong, as it doesn't reference which email contains it
      FileNamePredicate -> "a.txt",
      NamePredicate -> "a.txt",
      TextContentPredicate -> fileContentLikeFromTika(20, "Test attachment content", 1),
      FileFormatPredicate -> s"$PlainTextContentType; charset=US-ASCII"))

  private def testMbox(uri: URI) =
    createRDFModel(uri, GenericMediaFileType, Map(
      DataLocationPredicate -> HashOfTestEmailFile,
      FileNamePredicate -> TestEmailFileName.toString,
      FileFormatPredicate -> "application/mbox"))

  private def testEmailFileMetadata(uri: URI) = {
    def createEmailPerson(objectTypeName: String, email: String, maybeName: Option[String]): (BNode, Model) = {
      val nodeSubject = bnode()
      val node = new ModelBuilder().subject(nodeSubject).
        add(RDF.TYPE, asIRI(SchemaDotOrgBase.resolve(objectTypeName))).
        add(asIRI(EmailPredicate), email)

      nodeSubject -> maybeName.map(name => node.add(asIRI(NamePredicate), name)).getOrElse(node).build()
    }

    val (fromNodeSubject, fromNode) = createEmailPerson("Person", "alice.bobson@example.com", Some("Bobson,Alice"))
    val (to1NodeSubject, to1Node) = createEmailPerson("ContactPoint", "Bob Alison <bob.alison@example.com>", None)
    val (to2NodeSubject, to2Node) = createEmailPerson("ContactPoint", "John Doe <john.doe@example.com>", None)
    val (cc1NodeSubject, cc1Node) = createEmailPerson("ContactPoint", "Jane Doe <jane.doe@example.com>", None)
    val (cc2NodeSubject, cc2Node) = createEmailPerson("ContactPoint", "Joe Bloggs <joe.bloggs@example.com>", None)
    val emailPersonModel = new LinkedHashModel()

    //The presence of these two may be due to a bug in the tika parser...
    val (toWrongNodeSubject, toWrongNode) = createEmailPerson("ContactPoint", "Bob Alison <bob.alison@example.com>, John Doe <john.doe@example.com>", None)
    val (ccWrongNodeSubject, ccWrongNode) = createEmailPerson("ContactPoint", "Jane Doe <jane.doe@example.com>, Joe Bloggs <joe.bloggs@example.com>", None)

    Vector(fromNode, to1Node, to2Node, cc1Node, cc2Node, toWrongNode, ccWrongNode).foreach(emailPersonModel.addAll)

    createRDFModel_multipleValues(uri, EmailObjectType, Map(
      SenderPredicate -> Vector(fromNodeSubject),
        ToRecipientPredicate -> Vector(to1NodeSubject, to2NodeSubject, toWrongNodeSubject),
        CcRecipientPredicate -> Vector(cc1NodeSubject, cc2NodeSubject, ccWrongNodeSubject),
        DateSentPredicate -> Vector(literal("2001-09-21T08:46:31.000Z", XSD.DATETIME)),
        DataLocationPredicate -> Vector(HashOfTestEmailFile + "/"),
        FileNamePredicate -> Vector(""),
        FileFormatPredicate -> Vector(EmailContentType),
        TextContentPredicate -> Vector(fileContentLikeFromTika(33, "Test body content", 7))), Some(emailPersonModel))
  }

  private def testMP3FileMetadata(hash: String, prefixedNewlines: Int, lastModified: String)(uri: URI) = {
    val authorNodeSubject = bnode()
    val authorNode = new ModelBuilder().subject(authorNodeSubject).
      add(RDF.TYPE, asIRI(PersonObjectType)).
      add(asIRI(NamePredicate), "tela").build()

    createRDFModel(uri, MP3ObjectType, Map(
      DataLocationPredicate -> hash,
        FileNamePredicate -> TestMP3FileName.toString,
        FileFormatPredicate -> MP3ContentType,
        AuthorPredicate -> authorNodeSubject,
        GenrePredicate -> "Rock",
        NamePredicate -> "Short, Silent MP3",
        LastModifiedPredicate -> literal(lastModified, XSD.DATETIME),
        TextContentPredicate -> fileContentLikeFromTika(prefixedNewlines,
          "Short, Silent MP3\n\nShort, Silent MP3\ntela\nRock\n0.15673469\nXXX - \nSmall MP3 for testing tela", 1)),
        Some(authorNode))
  }

  private def testTextFileMetadata(hash: String, prefixedNewlines: Int, lastModified: String)(uri: URI) =
    createRDFModel(uri, GenericMediaFileType, Map(
      DataLocationPredicate -> hash,
      FileNamePredicate -> TestTextFileName.toString,
      TextContentPredicate -> fileContentLikeFromTika(prefixedNewlines, fileContent(TestTextFile), 1),
      FileFormatPredicate -> s"$PlainTextContentType; charset=ISO-8859-1",
      LastModifiedPredicate -> literal(lastModified, XSD.DATETIME),
      NamePredicate -> TestTextFileName))

  private def testZipFileMetadata(uri: URI) =
    createRDFModel(uri, GenericMediaFileType, Map(
      DataLocationPredicate -> HashOfTestZipFile,
      FileNamePredicate -> TestZipFileName.toString,
      FileFormatPredicate -> ZipFileContentType,
      TextContentPredicate -> (fileContentLikeFromTika(12, s"music/$TestMP3FileName", 1) + fileContentLikeFromTika(2, "testTextFile.zip", 2))))

  private def testInnerZipFileMetadata(uri: URI) =
    createRDFModel(uri, GenericMediaFileType, Map(
      DataLocationPredicate -> s"$HashOfTestZipFile/testTextFile.zip",
      FileNamePredicate -> "testTextFile.zip",
      FileFormatPredicate -> ZipFileContentType,
      TextContentPredicate -> fileContentLikeFromTika(20, TestTextFileName.toString, 2),
      LastModifiedPredicate -> literal("2022-07-22T12:57:01.000Z", XSD.DATETIME)))

  private def testTgzFileMetadata(uri: URI) =
    createRDFModel(uri, GenericMediaFileType, Map(
      DataLocationPredicate -> HashOfTestTgzFile,
      FileNamePredicate -> TestTgzFileName.toString,
      FileFormatPredicate -> GzipFileContentType,
      TextContentPredicate -> fileContentLikeFromTika(9, TestTarFileName, 2)))

  private def testTarFileMetadata(uri: URI) =
    createRDFModel(uri, GenericMediaFileType, Map(
      DataLocationPredicate -> s"$HashOfTestTgzFile/$TestTarFileName",
      FileNamePredicate -> TestTarFileName,
      FileFormatPredicate -> TarFileContentType,
      TextContentPredicate -> (fileContentLikeFromTika(14, s"mp3/music.tgz", 1) + fileContentLikeFromTika(2, TestTextFileName.toString, 2))))

  private def testMusicTarFileMetadata(uri: URI) =
    createRDFModel(uri, GenericMediaFileType, Map(
      DataLocationPredicate -> s"$HashOfTestTgzFile/$TestTarFileName/mp3/music.tgz/music.tar",
      FileNamePredicate -> "music.tar",
      FileFormatPredicate -> TarFileContentType,
      TextContentPredicate -> fileContentLikeFromTika(14, "music/testMP3.mp3", 2)))

  private def testMusicTgzFileMetadata(uri: URI) =
    createRDFModel(uri, GenericMediaFileType, Map(
      DataLocationPredicate -> s"$HashOfTestTgzFile/$TestTarFileName/mp3/music.tgz",
        FileNamePredicate -> "music.tgz",
        FileFormatPredicate -> GzipFileContentType,
        LastModifiedPredicate -> literal("2026-04-10T15:27:36.000Z", XSD.DATETIME),
        TextContentPredicate -> fileContentLikeFromTika(17, "music.tar", 2)))

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

    var uuids = Vector(TestUUID, AdditionalTestUUID1, AdditionalTestUUID2, AdditionalTestUUID3, AdditionalTestUUID4, AdditionalTestUUID5)
    val connection = Await.result(DataStoreConnectionImpl.getDataStore(BaseTestDir, TestUsername, GenericFileDataMap,
      Map(MP3ContentType -> MP3FileDataMap,
        ICalContentType -> ICalDataMap,
        PlainTextContentType -> PlainTextDataMap,
        EmailContentType -> EmailDataMap),
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
    insertJSON(testProfileInfoAsJSON(TestDataObjectUri), environment)
    assertURIStringContentsInDatastore(environment, TestDataObjectUri, testProfileInfoAsJSON)
    environment.connection.retrieveJSON(new URI("http://tela/nonExistant")) should beFutureJSONLDEquivalentTo("[]")
  }

  it should "not retrieve unrelated data" in testEnvironment { environment =>
    val otherPerson = s"""[
                         |    {
                         |        "@id": "uri:Other",
                         |        "@type": [ "http://xmlns.com/foaf/0.1/Person" ]
                         |    }
                         |]""".stripMargin

    insertJSON(testProfileInfoAsJSON(TestDataObjectUri), environment)
    insertJSON(otherPerson, environment)
    assertURIStringContentsInDatastore(environment, TestDataObjectUri, testProfileInfoAsJSON)
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
    insertJSON(testProfileInfoAsJSON(TestDataObjectUri), environment)
    assertURIStringContentsInDatastore(environment, TestDataObjectUri, testProfileInfoAsJSON)
  }

  "publish" should "publish given URI in XML format via XMPP" in testEnvironment { environment =>
    insertJSON(testProfileInfoAsJSON(TestDataObjectUri), environment)
    environment.connection.publish(TestDataObjectUri)

    verify(environment.xmppSession).publish(ArgumentMatchers.eq(TestDataObjectUri), argThat(new XMLMatcher(TestProfileInfoAsXML)))
  }

  private def insertJSON(json: String, environment: TestEnvironment): Unit = {
    Await.result(environment.connection.insertJSON(json), TestAwaitTimeout)
  }

  "getPublishedData" should "retrieve published data from XMPPSession and return response as JSON" in testEnvironment { environment =>
    when(environment.xmppSession.getPublishedData(TestUsername, TestDataObjectUri)).thenReturn(Future.successful(TestProfileInfoAsXML.toString))

    environment.connection.retrievePublishedDataAsJSON(TestUsername, TestDataObjectUri) should beFutureJSONLDEquivalentTo(testProfileInfoAsJSON(TestDataObjectUri))
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
    assertURIContentsInDatastore(environment, URNWithTestUUIDAsURI, testHTMLFileMetadata)
  }

  it should "store metadata from MP3 file in RDF store" in testEnvironment { environment =>
    createTempFileAndStoreContent(TestMP3, TestMP3FileName, Some(TestDateAsLocalDateTime), environment.connection)
    assertURIContentsInDatastore(environment, URNWithTestUUIDAsURI, testMP3FileMetadata(HashOfTestMP3, 22, TestDateWithIsoInstantFormat))
  }

  it should "extract appropriate metadata from ical content" in testEnvironment { environment =>
    createTempFileAndStoreContent(TestIcalFileWithEvent, TestIcalWithEventFileName, None, environment.connection)
    assertURIContentsInDatastore(environment, URNWithTestUUIDAsURI, testICalFileMetadata)
  }

  it should "extract appropriate metadata from email" in testEnvironment { environment =>
    createTempFileAndStoreContent(TestEmailFile, TestEmailFileName, None, environment.connection)
    assertURIContentsInDatastore(environment, Map(
      URNWithTestUUIDAsURI -> testMbox,
      AdditionalTestUUID1AsURN -> testEmailAttachment,
      AdditionalTestUUID2AsURN -> testEmailFileMetadata))
  }

  it should "store filename and last modified date" in testEnvironment { environment =>
    createTempFileAndStoreContent(TestTextFile, TestTextFileName, Some(TestDateAsLocalDateTime), environment.connection)
    assertURIContentsInDatastore(environment, URNWithTestUUIDAsURI, testTextFileMetadata(HashOfTestTextFile, 13, TestDateWithIsoInstantFormat))
  }

  it should "extract metadata for all contents in a compound file format and index text" in testEnvironment { environment =>
    createTempFileAndStoreContent(TestZipFile, TestZipFileName, None, environment.connection)
    assertURIContentsInDatastore(environment, Map(
      URNWithTestUUIDAsURI -> testZipFileMetadata,
      AdditionalTestUUID1AsURN -> testMP3FileMetadata(s"$HashOfTestZipFile/music/$TestMP3FileName", 29, TestFileLastModified),
      AdditionalTestUUID2AsURN -> testTextFileMetadata(s"$HashOfTestZipFile/testTextFile.zip/$TestTextFileName", 20, TestFileLastModified),
      AdditionalTestUUID3AsURN -> testInnerZipFileMetadata))
  }

  it should "store nested tgz" in testEnvironment { environment =>
    createTempFileAndStoreContent(TestDataRoot.resolve(TestTgzFileName), TestTgzFileName, None, environment.connection)
    assertURIContentsInDatastore(environment, Map(URNWithTestUUIDAsURI -> testTgzFileMetadata,
      AdditionalTestUUID1AsURN -> testMP3FileMetadata(s"$HashOfTestTgzFile/$TestTarFileName/mp3/music.tgz/music.tar/music/$TestMP3FileName", 29, TestFileLastModified),
      AdditionalTestUUID2AsURN -> testMusicTarFileMetadata,
      AdditionalTestUUID3AsURN -> testMusicTgzFileMetadata,
      AdditionalTestUUID4AsURN -> testTextFileMetadata(s"$HashOfTestTgzFile/$TestTarFileName/$TestTextFileName", 20, TestFileLastModified),
      AdditionalTestUUID5AsURN -> testTarFileMetadata))
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
    insertJSON(testProfileInfoAsJSON(TestDataObjectUri), environment)

    // We search for "walking" even though the name is "Walks" to verify that stemming works for both content and queries
    environment.connection.runSPARQLQuery("PREFIX search: <http://www.openrdf.org/contrib/lucenesail#>\n" +
      "PREFIX foaf: <http://xmlns.com/foaf/0.1/>\n" +
      "CONSTRUCT { } WHERE { ?s foaf:familyName ?o . ?s search:matches [ search:query \"walking\" ] }") should beFutureJSONLDEquivalentTo(testFamilyNameFromProfileInfo(TestDataObjectUri))
  }

  it should "handle GeoSparql queries" in testEnvironment { environment =>
    //At the time of writing the LuceneSail is the only Sail that supports GeoSparql

    val valueFactory = SimpleValueFactory.getInstance()

    val graphWithRestaurant = new LinkedHashModel()
    graphWithRestaurant.add(valueFactory.createIRI("http://leVinCoeur"), GeoCoordinatesPredicateIri, valueFactory.createLiteral("POINT (2.29397 48.87510)", GeoCoordinatesLiteralType))

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

  private def assertURIContentsInDatastore(environment: TestEnvironment, uri: URI, expectedContents: URI => Model): Unit = {
    environment.connection.retrieveJSON(uri) should beFutureJSONLDEquivalentTo(expectedContents(uri))
  }

  private def assertURIContentsInDatastore(environment: TestEnvironment, expectations: Map[URI, URI => Model]): Unit = {
    expectations.foreach((uri, expectedContents) => assertURIContentsInDatastore(environment, uri, expectedContents))
  }

  private def assertURIStringContentsInDatastore(environment: TestEnvironment, uri: URI, expectedContents: URI => String): Unit = {
    environment.connection.retrieveJSON(uri) should beFutureJSONLDEquivalentTo(expectedContents(uri))
  }

  private def beFutureJSONLDEquivalentTo(expectedValue: Model): Matcher[Future[String]] = {
    new Matcher[Future[String]]() {
      override def apply(left: Future[String]): MatchResult = {
        compareModels(expectedValue, getJSONAsRDFModel(Await.result(left, TestAwaitTimeout)))
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
  private def fileContentLikeFromTika(prefixedNewlines: Int, content: String, postfixedNewlines: Int) =
    "\n".repeat(prefixedNewlines) + content + "\n".repeat(postfixedNewlines)

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
