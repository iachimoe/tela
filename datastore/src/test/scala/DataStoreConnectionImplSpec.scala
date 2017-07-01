package tela.datastore

import java.io.File
import java.net.URI
import java.nio.file.{Files, Paths, StandardCopyOption}

import org.apache.lucene.store.AlreadyClosedException
import org.eclipse.rdf4j.model.impl.{LinkedHashModel, SimpleValueFactory}
import org.eclipse.rdf4j.model.vocabulary.{GEO, GEOF, XMLSchema}
import org.eclipse.rdf4j.rio.RDFFormat
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.mockito.{ArgumentMatcher, ArgumentMatchers}
import org.scalatest.Matchers._
import tela.baseinterfaces.{ComplexObject, XMPPSession}

import scala.xml._

class DataStoreConnectionImplSpec extends DataStoreBaseSpec {
  private val BaseTestDir = TestDataRoot + "/store"
  private val NonExistentDataStore = TestDataRoot + "/nonExistent"

  private val TestHtmlFileName = "testHTMLFile.html"
  private val TestHtmlFile = TestDataRoot + "/" + TestHtmlFileName
  private val HashOfTestHtmlFile = "85b94c9ff01e60d63fa7005bf2c9625f4a437024"

  private val TestTextFileName = "testTextFile.txt"
  private val TestTextFile = TestDataRoot + "/" + TestTextFileName
  private val HashOfTestTextFile = "09fac8dbfd27bd9b4d23a00eb648aa751789536d"

  private val TestMP3FileName = "testMP3.mp3"
  private val TestMP3 = TestDataRoot + "/" + TestMP3FileName
  private val HashOfTestMP3 = "a82e3d27ec0184d28b0de85a70242e9985213143"

  private val TestUUID = "aaaaaaaaa"
  private val URNWithTestUUID = DataStoreConnectionImpl.URNBaseForUUIDs + TestUUID

  private val TestMediaItemsRoot = BaseTestDir + "/" + TestUsername + "/" + DataStoreConnectionImpl.MediaItemsFolderName

  private val TestProfileInfoAsJSON = s"""[
                                  |    {
                                  |        "@id": "${TestDataObjectUri}",
                                  |        "@type": [ "http://xmlns.com/foaf/0.1/Person" ],
                                  |        "http://xmlns.com/foaf/0.1/familyName": [
                                  |            {
                                  |                "@value": "Foo"
                                  |            }
                                  |        ],
                                  |        "http://xmlns.com/foaf/0.1/firstName": [
                                  |            {
                                  |                "@value": "Bar"
                                  |            }
                                  |        ]
                                  |    }
                                  |]""".stripMargin

  private val TestHTMLFileMetadataAsJSON = s"""[
                                  |    {
                                  |        "@id": "$URNWithTestUUID",
                                  |        "@type": [ "$GenericMediaFileType" ],
                                  |        "$HashPredicate": [
                                  |            {
                                  |                "@value": "$HashOfTestHtmlFile"
                                  |            }
                                  |        ],
                                  |        "$FileFormatPredicate": [
                                  |            {
                                  |                "@value": "$TextHtmlContentType"
                                  |            }
                                  |        ]
                                  |    }
                                  |]""".stripMargin

  private val TestICalFileMetadataAsJSON = s"""[
                                               |    {
                                               |        "@id": "$URNWithTestUUID",
                                               |        "@type": [ "$GenericMediaFileType" ],
                                               |        "$HashPredicate": [
                                               |            {
                                               |                "@value": "$HashOfTestIcalFile"
                                               |            }
                                               |        ],
                                               |        "$FileFormatPredicate": [
                                               |            {
                                               |                "@value": "$ICalContentType"
                                               |            }
                                               |        ],
                                               |        "http://schema.org/name": [
                                               |            {
                                               |                "@value": "DDD London #3 - Strategic and Collaborative Domain-Driven Design"
                                               |            }
                                               |        ]
                                               |    }
                                               |]""".stripMargin

  // We give the blank node an arbitrary id of "_:BLANKNODE".
  // RDF4J will represent it with a different id internally,
  // but the graph comparison functions seem to be smart enough to
  // recognise when two blank nodes can be considered "equal"
  private val TestMP3FileMetadataAsJSON = s"""[{
                                  |         "@id" : "_:BLANKNODE",
                                  |         "@type" : [ "http://schema.org/Person" ],
                                  |         "http://schema.org/name" : [ {
                                  |           "@value" : "tela"
                                  |         } ]
                                  |    },
                                  |    {
                                  |        "@id": "$URNWithTestUUID",
                                  |        "@type": [ "$MP3ObjectType" ],
                                  |        "http://schema.org/author": [
                                  |            {
                                  |                "@id" : "_:BLANKNODE"
                                  |            }
                                  |        ],
                                  |        "$FileFormatPredicate": [
                                  |            {
                                  |                "@value": "$MP3ContentType"
                                  |            }
                                  |        ],
                                  |        "http://schema.org/genre": [
                                  |            {
                                  |                "@value": "Rock"
                                  |            }
                                  |        ],
                                  |        "http://schema.org/name": [
                                  |            {
                                  |                "@value": "Short, Silent MP3"
                                  |            }
                                  |        ],
                                  |        "$HashPredicate": [
                                  |            {
                                  |                "@value": "$HashOfTestMP3"
                                  |            }
                                  |        ]
                                  |    }
                                  |]""".stripMargin

  private val TestTextFileMetadataAsJSON = s"""[
                                              |    {
                                              |        "@id": "$URNWithTestUUID",
                                              |        "@type": [ "$GenericMediaFileType" ],
                                              |        "$HashPredicate": [
                                              |            {
                                              |                "@value": "$HashOfTestTextFile"
                                              |            }
                                              |        ],
                                              |        "$FileFormatPredicate": [
                                              |            {
                                              |                "@value": "$PlainTextContentType"
                                              |            }
                                              |        ],
                                              |        "http://schema.org/name": [
                                              |            {
                                              |                "@value": "$TestTextFileName"
                                              |            }
                                              |        ]
                                              |    }
                                              |]""".stripMargin

  private val TestProfileInfoAsXML = <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about={TestDataObjectUri}>
      <rdf:type rdf:resource="http://xmlns.com/foaf/0.1/Person"/>
      <familyName xmlns="http://xmlns.com/foaf/0.1/" rdf:datatype="http://www.w3.org/2001/XMLSchema#string">Foo</familyName>
      <firstName xmlns="http://xmlns.com/foaf/0.1/" rdf:datatype="http://www.w3.org/2001/XMLSchema#string">Bar</firstName>
    </rdf:Description>
  </rdf:RDF>

  private class TestEnvironment(val connection: DataStoreConnectionImpl, val xmppSession: XMPPSession)

  private def testEnvironment(runTest: (TestEnvironment) => Unit) = {
    recursiveDelete(new File(BaseTestDir, TestUsername))
    recursiveDelete(new File(NonExistentDataStore))

    val xmppSession = mock[XMPPSession]
    val connection = DataStoreConnectionImpl.getDataStore(BaseTestDir, TestUsername, GenericFileDataMap,
      Map(MP3ContentType -> MP3FileDataMap, ICalContentType -> ICalDataMap, PlainTextContentType -> PlainTextDataMap),
      xmppSession, TestTikaConfigFile, () => TestUUID)

    try {
      runTest(new TestEnvironment(connection, xmppSession))
    } finally {
      cleanUpDataStore(connection)
    }
  }

  private def cleanUpDataStore(connection: DataStoreConnectionImpl): Unit = {
    connection.closeConnection()
    connection.connection.isOpen should === (false)
    connection.repository.isInitialized should === (false)
    connection.luceneIndexReader.getRefCount should === (0)

    var writerClosed = false
    try {
      connection.luceneIndexWriter.numDocs()
    } catch {
      case _: AlreadyClosedException => writerClosed = true
    }
    writerClosed should === (true)
  }

  "getDataStore" should "throw an IllegalArgumentException for a non-existent data store" in testEnvironment { environment =>
    assertThrows[IllegalArgumentException] {
      DataStoreConnectionImpl.getDataStore(NonExistentDataStore, TestUsername, ComplexObject(new URI(""), Map()), Map(), environment.xmppSession, TestTikaConfigFile, () => "")
    }
  }

  "retrieveJson" should "return an empty array when an arbitrary URI is requested from an empty store" in testEnvironment { environment =>
    assertJSONGraphsAreEqual("[]", environment.connection.retrieveJSON(TestDataObjectUri))
  }

  it should "retrieve the same JSONLD graph that was inserted when the URI of that graph is requested" in testEnvironment { environment =>
    environment.connection.insertJSON(TestProfileInfoAsJSON)
    assertJSONGraphsAreEqual(TestProfileInfoAsJSON, environment.connection.retrieveJSON(TestDataObjectUri))
    assertJSONGraphsAreEqual("[]", environment.connection.retrieveJSON("http://tela/nonExistant"))
  }

  it should "not retrieve unrelated data" in testEnvironment { environment =>
    val otherPerson = s"""[
                         |    {
                         |        "@id": "uri:Other",
                         |        "@type": [ "http://xmlns.com/foaf/0.1/Person" ]
                         |    }
                         |]""".stripMargin

    environment.connection.insertJSON(TestProfileInfoAsJSON)
    environment.connection.insertJSON(otherPerson)
    assertJSONGraphsAreEqual(TestProfileInfoAsJSON, environment.connection.retrieveJSON(TestDataObjectUri))
  }

  "insertJson" should "overwrite old content when new content is inserted with an pre-existing URI" in testEnvironment { environment =>
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

    environment.connection.insertJSON(alternateData)
    environment.connection.insertJSON(TestProfileInfoAsJSON)
    assertJSONGraphsAreEqual(TestProfileInfoAsJSON, environment.connection.retrieveJSON(TestDataObjectUri))
  }

  "publish" should "publish given URI in XML format via XMPP" in testEnvironment { environment =>
    environment.connection.insertJSON(TestProfileInfoAsJSON)
    environment.connection.publish(TestDataObjectUri)

    verify(environment.xmppSession).publish(ArgumentMatchers.eq(TestDataObjectUri), argThat(new XMLMatcher(TestProfileInfoAsXML)))
  }

  "getPublishedData" should "retrieve published data from XMPPSession and return response as JSON" in testEnvironment { environment =>
    when(environment.xmppSession.getPublishedData(TestUsername, TestDataObjectUri)).thenReturn(TestProfileInfoAsXML.toString)

    assertJSONGraphsAreEqual(TestProfileInfoAsJSON, environment.connection.retrievePublishedDataAsJSON(TestUsername, TestDataObjectUri))
  }

  "storeMediaItem" should "place contents of temporary file in data store with correct hash and remove temporary file" in testEnvironment { environment =>
    val tempFile = createTempFileAndStoreContent(TestHtmlFile, TestHtmlFileName, environment.connection)
    new File(TestMediaItemsRoot, HashOfTestHtmlFile).exists() should === (true)
    tempFile.exists() should === (false)
  }

  it should "be able to store multiple files" in testEnvironment { environment =>
    createTempFileAndStoreContent(TestHtmlFile, TestHtmlFileName, environment.connection)
    createTempFileAndStoreContent(TestMP3, TestMP3FileName, environment.connection)
    createTempFileAndStoreContent(TestMP3, TestMP3FileName, environment.connection) //verifying that storing the same file twice won't cause an exception

    new File(TestMediaItemsRoot, HashOfTestHtmlFile).exists() should === (true)
    new File(TestMediaItemsRoot, HashOfTestMP3).exists() should === (true)
  }

  "retrieveMediaItem" should "return the absolute path if the requested file if it exists in the data store" in testEnvironment { environment =>
    createTempFileAndStoreContent(TestHtmlFile, TestHtmlFileName, environment.connection)
    environment.connection.retrieveMediaItem(HashOfTestHtmlFile) should === (Some(new File(TestMediaItemsRoot, HashOfTestHtmlFile).getAbsolutePath))
  }

  it should "return None for a non-existent media item" in testEnvironment { environment =>
    createTempFileAndStoreContent(TestHtmlFile, TestHtmlFileName, environment.connection) //adding this file to ensure that data store exists
    environment.connection.retrieveMediaItem("notARealHash") should === (None)
  }

  it should "prohibit attempts to retreive files from other folders" in testEnvironment { environment =>
    createTempFileAndStoreContent(TestHtmlFile, TestHtmlFileName, environment.connection) //adding this file to ensure that data store exists
    environment.connection.retrieveMediaItem("../../.gitignore") should === (None)
  }

  it should "store UUID, hash and file format of media items in RDF store" in testEnvironment { environment =>
    createTempFileAndStoreContent(TestHtmlFile, TestHtmlFileName, environment.connection)
    assertJSONGraphsAreEqual(TestHTMLFileMetadataAsJSON, environment.connection.retrieveJSON(URNWithTestUUID))
  }

  it should "store metadata from MP3 file in RDF store" in testEnvironment { environment =>
    createTempFileAndStoreContent(TestMP3, TestMP3FileName, environment.connection)
    assertJSONGraphsAreEqual(TestMP3FileMetadataAsJSON, environment.connection.retrieveJSON(URNWithTestUUID))
  }

  "runSPARQLQuery" should "return empty array for query against empty repository" in testEnvironment { environment =>
    environment.connection.runSPARQLQuery(WildcardSparqlQuery) should === ("[]")
  }

  it should "return data in JSONLD format" in testEnvironment { environment =>
    environment.connection.insertJSON(TestProfileInfoAsJSON)

    val result = environment.connection.runSPARQLQuery("PREFIX foaf: <http://xmlns.com/foaf/0.1/>\n" +
      "CONSTRUCT { ?s foaf:familyName ?o } WHERE {?s foaf:familyName ?o}")

    result should === ("""[{"@id":"http://tela/profileInfo","http://xmlns.com/foaf/0.1/familyName":[{"@value":"Foo"}]}]""")
  }

  it should "handle GeoSparql queries" in testEnvironment { environment =>
    //At the time of writing the LuceneSail is the only Sail that supports GeoSparql

    val valueFactory = SimpleValueFactory.getInstance()

    val graphWithRestaurant = new LinkedHashModel()
    graphWithRestaurant.add(valueFactory.createIRI("http://leVinCoeur"), GEO.AS_WKT, valueFactory.createLiteral("POINT (2.29397 48.87510)", GEO.WKT_LITERAL))

    val graphWithRestaurantAsJson: String = DataStoreConnectionImpl.convertRDFModelToJson(graphWithRestaurant)
    environment.connection.insertJSON(graphWithRestaurantAsJson)

    val placesNearArcDeTriompheQuery =
      "prefix geo: <" + GEO.NAMESPACE + ">" +
        "prefix geof: <" + GEOF.NAMESPACE + ">" +
        "prefix uom: <" + GEOF.UOM_NAMESPACE + ">" +
        "prefix xsd: <" + XMLSchema.NAMESPACE + ">" +
        "DESCRIBE ?subject where { ?subject geo:asWKT ?object . filter(geof:distance(\"POINT (2.2950 48.8738)\"^^geo:wktLiteral, ?object, uom:metre) < \"500.0\"^^xsd:double) }"

    val result: String = environment.connection.runSPARQLQuery(placesNearArcDeTriompheQuery)
    assertJSONGraphsAreEqual(graphWithRestaurantAsJson, result)
  }

  it should "extract appropriate metadata from ical content" in testEnvironment { environment =>
    createTempFileAndStoreContent(TestIcalFileWithEvent, TestIcalWithEventFileName, environment.connection)
    assertJSONGraphsAreEqual(TestICalFileMetadataAsJSON, environment.connection.retrieveJSON(URNWithTestUUID))
  }

  it should "store filename" in testEnvironment { environment =>
    createTempFileAndStoreContent(TestTextFile, TestTextFileName, environment.connection)
    assertJSONGraphsAreEqual(TestTextFileMetadataAsJSON, environment.connection.retrieveJSON(URNWithTestUUID))
  }

  it should "index text which is then searchable by means of textSearch method" in testEnvironment { environment =>
    createTempFileAndStoreContent(TestTextFile, TestTextFileName, environment.connection)
    environment.connection.textSearch("hello") should === (List(HashOfTestTextFile))

    createTempFileAndStoreContent(TestHtmlFile, TestHtmlFileName, environment.connection)
    environment.connection.textSearch("hello").sortBy(s => s) should === (List(HashOfTestTextFile, HashOfTestHtmlFile).sortBy(s => s))

    environment.connection.textSearch("world") should === (List(HashOfTestTextFile))
  }

  private def createTempFileAndStoreContent(path: String, originalFileName: String, connection: DataStoreConnectionImpl): File = {
    val tempFile = File.createTempFile("aaa", "")
    Files.copy(Paths.get(path), Paths.get(tempFile.getAbsolutePath), StandardCopyOption.REPLACE_EXISTING)
    connection.storeMediaItem(tempFile.getAbsolutePath, originalFileName)
    tempFile
  }

  private def assertJSONGraphsAreEqual(expected: String, actual: String): Unit = {
    getJSONAsRDFModel(actual) should === (getJSONAsRDFModel(expected))
  }

  private def getJSONAsRDFModel(json: String): LinkedHashModel = {
    DataStoreConnectionImpl.convertDataToRDFModel(json, RDFFormat.JSONLD)
  }

  private def recursiveDelete(file: File): Unit = {
    val children: Array[String] = file.list
    if (children != null)
      children.foreach((name: String) => recursiveDelete(new File(file, name)))
    file.delete()
  }

  private class XMLMatcher(private val expected: Elem) extends ArgumentMatcher[String] {
    override def matches(argument: String): Boolean = {
      argument match {
        case actual: String =>
          //Ensure that the result doesn't start with a declaration so that we can easily embed it in another XML doc
          !actual.startsWith("<?xml") && Utility.trim(expected) == Utility.trim(XML.loadString(actual))
        case _ => false
      }
    }
  }
}
