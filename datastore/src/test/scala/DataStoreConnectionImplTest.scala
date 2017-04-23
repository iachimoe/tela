package tela.datastore

import java.io.File
import java.net.URI
import java.nio.file.{Files, Paths, StandardCopyOption}

import org.apache.lucene.store.AlreadyClosedException
import org.junit.Assert._
import org.junit.{After, Before, Test}
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.mockito.{ArgumentMatcher, ArgumentMatchers}
import org.eclipse.rdf4j.model.impl.{LinkedHashModel, SimpleValueFactory}
import org.eclipse.rdf4j.model.vocabulary.{GEO, GEOF, XMLSchema}
import org.eclipse.rdf4j.rio.RDFFormat
import org.scalatest.junit.AssertionsForJUnit
import org.scalatest.mock.MockitoSugar
import tela.baseinterfaces.{ComplexObject, XMPPSession}
import tela.datastore.TestData._

import scala.xml._

class DataStoreConnectionImplTest extends AssertionsForJUnit with MockitoSugar {
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

  private val TestUser = "foo"
  private val TestMediaItemsRoot = BaseTestDir + "/" + TestUser + "/" + DataStoreConnectionImpl.MediaItemsFolderName

  private val TestPublicationURI = "http://tela/profileInfo"

  private val TestProfileInfoAsJSON = s"""[
                                  |    {
                                  |        "@id": "$TestPublicationURI",
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
                                  |                "@value": "$HtmlContentType"
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

  val TestProfileInfoAsXML = <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about={TestPublicationURI}>
      <rdf:type rdf:resource="http://xmlns.com/foaf/0.1/Person"/>
      <familyName xmlns="http://xmlns.com/foaf/0.1/" rdf:datatype="http://www.w3.org/2001/XMLSchema#string">Foo</familyName>
      <firstName xmlns="http://xmlns.com/foaf/0.1/" rdf:datatype="http://www.w3.org/2001/XMLSchema#string">Bar</firstName>
    </rdf:Description>
  </rdf:RDF>

  private var connection: DataStoreConnectionImpl = null
  private var xmppSession: XMPPSession = null

  @Before def initialize(): Unit = {
    recursiveDelete(new File(BaseTestDir, TestUser))
    recursiveDelete(new File(NonExistentDataStore))
    xmppSession = mock[XMPPSession]
    connection = DataStoreConnectionImpl.getDataStore(BaseTestDir, TestUser, GenericFileDataMap,
      Map(MP3ContentType -> MP3FileDataMap, ICalContentType -> ICalDataMap, PlainTextContentType -> PlainTextDataMap),
      xmppSession, TestTikaConfigFile, () => TestUUID)
  }

  @After def cleanUp(): Unit = {
    connection.closeConnection()
    assertFalse(connection.connection.isOpen)
    assertFalse(connection.repository.isInitialized)
    assertEquals(0, connection.luceneIndexReader.getRefCount)

    var writerClosed = false
    try {
      connection.luceneIndexWriter.numDocs()
    } catch {
      case _: AlreadyClosedException => writerClosed = true
    }
    assertTrue(writerClosed)
  }

  @Test(expected = classOf[IllegalArgumentException]) def nonExistantDataStore(): Unit = {
    DataStoreConnectionImpl.getDataStore(NonExistentDataStore, TestUser, ComplexObject(new URI(""), Map()), Map(), xmppSession, TestTikaConfigFile, () => "")
  }

  @Test def retrieveJSONFromEmptyStore(): Unit = {
    assertJSONGraphsAreEqual("[]", connection.retrieveJSON(TestPublicationURI))
  }

  @Test def retrieveJSON(): Unit = {
    connection.insertJSON(TestProfileInfoAsJSON)
    assertJSONGraphsAreEqual(TestProfileInfoAsJSON, connection.retrieveJSON(TestPublicationURI))
    assertJSONGraphsAreEqual("[]", connection.retrieveJSON("http://tela/nonExistant"))
  }

  @Test def retrieveJSONDoesNotRetrieveUnrelatedData(): Unit = {
    val otherPerson = s"""[
                        |    {
                        |        "@id": "uri:Other",
                        |        "@type": [ "http://xmlns.com/foaf/0.1/Person" ]
                        |    }
                        |]""".stripMargin

    connection.insertJSON(TestProfileInfoAsJSON)
    connection.insertJSON(otherPerson)
    assertJSONGraphsAreEqual(TestProfileInfoAsJSON, connection.retrieveJSON(TestPublicationURI))
  }

  @Test def oldContentGetsDeleted(): Unit = {
    val alternateData = s"""[
                          |    {
                          |        "@id": "$TestPublicationURI",
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

    connection.insertJSON(alternateData)
    connection.insertJSON(TestProfileInfoAsJSON)
    assertJSONGraphsAreEqual(TestProfileInfoAsJSON, connection.retrieveJSON(TestPublicationURI))
  }

  @Test def publish(): Unit = {
    connection.insertJSON(TestProfileInfoAsJSON)
    connection.publish(TestPublicationURI)

    verify(xmppSession).publish(ArgumentMatchers.eq(TestPublicationURI), argThat(new XMLMatcher(TestProfileInfoAsXML)))
  }

  @Test def retrievePublishedDataAsJSON(): Unit = {
    when(xmppSession.getPublishedData(TestUser, TestPublicationURI)).thenReturn(TestProfileInfoAsXML.toString)

    assertJSONGraphsAreEqual(TestProfileInfoAsJSON, connection.retrievePublishedDataAsJSON(TestUser, TestPublicationURI))
  }

  @Test def storeMediaItem(): Unit = {
    val tempFile = createTempFileAndStoreContent(TestHtmlFile, TestHtmlFileName)
    assertTrue(new File(TestMediaItemsRoot, HashOfTestHtmlFile).exists())
    assertFalse(tempFile.exists())
  }

  @Test def storeMultipleMediaItems(): Unit = {
    createTempFileAndStoreContent(TestHtmlFile, TestHtmlFileName)
    createTempFileAndStoreContent(TestMP3, TestMP3FileName)
    createTempFileAndStoreContent(TestMP3, TestMP3FileName) //verifying that storing the same file twice won't cause an exception

    assertTrue(new File(TestMediaItemsRoot, HashOfTestHtmlFile).exists())
    assertTrue(new File(TestMediaItemsRoot, HashOfTestMP3).exists())
  }

  @Test def retrieveMediaItem(): Unit = {
    createTempFileAndStoreContent(TestHtmlFile, TestHtmlFileName)
    assertEquals(Some(new File(TestMediaItemsRoot, HashOfTestHtmlFile).getAbsolutePath), connection.retrieveMediaItem(HashOfTestHtmlFile))
  }

  @Test def retrieveNonExistentMediaItem(): Unit = {
    createTempFileAndStoreContent(TestHtmlFile, TestHtmlFileName) //adding this file to ensure that data store exists
    assertEquals(None, connection.retrieveMediaItem("notARealHash"))
  }

  @Test def prohibitAttemptsToRetrieveFilesFromOtherFolders(): Unit = {
    createTempFileAndStoreContent(TestHtmlFile, TestHtmlFileName) //adding this file to ensure that data store exists
    assertEquals(None, connection.retrieveMediaItem("../../.gitignore"))
  }

  @Test def storeUUIDAndHashAndFileFormatOfMediaItemsInRDFStore(): Unit = {
    createTempFileAndStoreContent(TestHtmlFile, TestHtmlFileName)
    assertJSONGraphsAreEqual(TestHTMLFileMetadataAsJSON, connection.retrieveJSON(URNWithTestUUID))
  }

  @Test def storeMetadataFromMP3FileInRDFStore(): Unit = {
    createTempFileAndStoreContent(TestMP3, TestMP3FileName)
    assertJSONGraphsAreEqual(TestMP3FileMetadataAsJSON, connection.retrieveJSON(URNWithTestUUID))
  }

  @Test def sparqlQueryWithEmptyResult(): Unit = {
    assertEquals("[]", connection.runSPARQLQuery("CONSTRUCT { ?s ?p ?o } WHERE {?s ?p ?o }"))
  }

  @Test def sparqlQuery(): Unit = {
    connection.insertJSON(TestProfileInfoAsJSON)

    val result = connection.runSPARQLQuery("PREFIX foaf: <http://xmlns.com/foaf/0.1/>\n" +
      "CONSTRUCT { ?s foaf:familyName ?o } WHERE {?s foaf:familyName ?o}")

    assertEquals("""[{"@id":"http://tela/profileInfo","http://xmlns.com/foaf/0.1/familyName":[{"@value":"Foo"}]}]""", result)
  }

  @Test def geoSparql(): Unit = {
    //At the time of writing the LuceneSail is the only Sail that supports GeoSparql

    val valueFactory = SimpleValueFactory.getInstance()

    val graphWithRestaurant = new LinkedHashModel()
    graphWithRestaurant.add(valueFactory.createIRI("http://leVinCoeur"), GEO.AS_WKT, valueFactory.createLiteral("POINT (2.29397 48.87510)", GEO.WKT_LITERAL))

    val graphWithRestaurantAsJson: String = DataStoreConnectionImpl.convertRDFModelToJson(graphWithRestaurant)
    connection.insertJSON(graphWithRestaurantAsJson)

    val placesNearArcDeTriompheQuery =
      "prefix geo: <" + GEO.NAMESPACE + ">" +
      "prefix geof: <" + GEOF.NAMESPACE + ">" +
      "prefix uom: <" + GEOF.UOM_NAMESPACE + ">" +
      "prefix xsd: <" + XMLSchema.NAMESPACE + ">" +
      "DESCRIBE ?subject where { ?subject geo:asWKT ?object . filter(geof:distance(\"POINT (2.2950 48.8738)\"^^geo:wktLiteral, ?object, uom:metre) < \"500.0\"^^xsd:double) }"

    val result: String = connection.runSPARQLQuery(placesNearArcDeTriompheQuery)
    assertJSONGraphsAreEqual(graphWithRestaurantAsJson, result)
  }

  @Test def ical(): Unit = {
    createTempFileAndStoreContent(TestIcalFileWithEvent, TestIcalWithEventFileName)
    assertJSONGraphsAreEqual(TestICalFileMetadataAsJSON, connection.retrieveJSON(URNWithTestUUID))
  }

  @Test def filenameIsStoredIfConfiguredForFileType(): Unit = {
    createTempFileAndStoreContent(TestTextFile, TestTextFileName)
    assertJSONGraphsAreEqual(TestTextFileMetadataAsJSON, connection.retrieveJSON(URNWithTestUUID))
  }

  @Test def textIsSearchable(): Unit = {
    createTempFileAndStoreContent(TestTextFile, TestTextFileName)
    assertEquals(List(HashOfTestTextFile), connection.textSearch("hello"))

    createTempFileAndStoreContent(TestHtmlFile, TestHtmlFileName)
    assertEquals(List(HashOfTestTextFile, HashOfTestHtmlFile).sortBy(s => s), connection.textSearch("hello").sortBy(s => s))

    assertEquals(List(HashOfTestTextFile), connection.textSearch("world"))
  }

  private def createTempFileAndStoreContent(path: String, originalFileName: String): File = {
    val tempFile = File.createTempFile("aaa", "")
    Files.copy(Paths.get(path), Paths.get(tempFile.getAbsolutePath), StandardCopyOption.REPLACE_EXISTING)
    connection.storeMediaItem(tempFile.getAbsolutePath, originalFileName)
    tempFile
  }

  private def assertJSONGraphsAreEqual(expected: String, actual: String): Unit = {
    val expGraph: LinkedHashModel = getJSONAsRDFModel(expected)
    val actGraph: LinkedHashModel = getJSONAsRDFModel(actual)
    if (expGraph != actGraph) {
      fail("expected: " + expected + System.lineSeparator + "actual: " + actual)
    }
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
