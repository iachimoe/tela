package tela.datastore

import java.io.{File, StringReader}

import org.junit.Assert._
import org.junit.{After, Before, Test}
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.mockito.{ArgumentMatcher, Matchers}
import org.openrdf.model.impl.LinkedHashModel
import org.openrdf.rio.helpers.StatementCollector
import org.openrdf.rio.{RDFFormat, Rio}
import org.scalatest.junit.AssertionsForJUnit
import org.scalatest.mock.MockitoSugar
import tela.baseinterfaces.XMPPSession

import scala.xml._

class DataStoreConnectionImplTest extends AssertionsForJUnit with MockitoSugar {
  private val BaseTestDir = "datastore/src/test/data/store"
  private val NonExistantDataStore = "datastore/src/test/data/nonExistant"
  private val TestUser = "foo"

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
    recursiveDelete(new File(NonExistantDataStore))
    xmppSession = mock[XMPPSession]
    connection = DataStoreConnectionImpl.getDataStore(BaseTestDir, TestUser, xmppSession)
  }

  @After def cleanUp(): Unit = {
    connection.closeConnection()
    assertFalse(connection.connection.isOpen)
    assertFalse(connection.repository.isInitialized)
  }

  @Test(expected = classOf[IllegalArgumentException]) def nonExistantDataStore(): Unit = {
    DataStoreConnectionImpl.getDataStore(NonExistantDataStore, TestUser, xmppSession)
  }

  @Test def retrieveJSONFromEmptyStore(): Unit = {
    assertJSONGraphsAreEqual("[]", connection.retrieveJSON(TestPublicationURI))
  }

  @Test def retrieveJSON(): Unit = {
    connection.insertJSON(TestProfileInfoAsJSON)
    assertJSONGraphsAreEqual(TestProfileInfoAsJSON, connection.retrieveJSON(TestPublicationURI))
    assertJSONGraphsAreEqual("[]", connection.retrieveJSON("http://tela/nonExistant"))
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

    verify(xmppSession).publish(Matchers.eq(TestPublicationURI), argThat(new XMLMatcher(TestProfileInfoAsXML)))
  }

  @Test def retrievePublishedDataAsJSON(): Unit = {
    when(xmppSession.getPublishedData(TestUser, TestPublicationURI)).thenReturn(TestProfileInfoAsXML.toString)

    assertJSONGraphsAreEqual(TestProfileInfoAsJSON, connection.retrievePublishedDataAsJSON(TestUser, TestPublicationURI))
  }

  private def assertJSONGraphsAreEqual(expected: String, actual: String): Unit = {
    if (getJSONAsRDFMap(expected) != getJSONAsRDFMap(actual)) {
      fail("expected: " + expected + System.lineSeparator + "actual: " + actual)
    }
  }

  private def getJSONAsRDFMap(json: String): LinkedHashModel = {
    val rdfParser = Rio.createParser(RDFFormat.JSONLD)
    val map = new LinkedHashModel()
    rdfParser.setRDFHandler(new StatementCollector(map))
    rdfParser.parse(new StringReader(json), null)
    map
  }

  private def recursiveDelete(file: File): Unit = {
    val children: Array[String] = file.list
    if (children != null)
      children.map((name: String) => recursiveDelete(new File(file, name)))
    file.delete()
  }

  private class XMLMatcher(private val expected: Elem) extends ArgumentMatcher[String] {
    override def matches(argument: Any): Boolean = {
      argument match {
        case actual: String =>
          //Ensure that the result doesn't start with a declaration so that we can easily embed it in another XML doc
          !actual.startsWith("<?xml") && Utility.trim(expected) == Utility.trim(XML.loadString(actual))
        case _ => false
      }
    }
  }

}
