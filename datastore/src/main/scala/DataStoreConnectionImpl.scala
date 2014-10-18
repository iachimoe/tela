package tela.datastore

import java.io.{File, StringReader, StringWriter}

import com.typesafe.scalalogging.Logger
import info.aduna.iteration.Iterations
import org.openrdf.model.impl.LinkedHashModel
import org.openrdf.repository.sail.SailRepository
import org.openrdf.rio.helpers.{StatementCollector, XMLWriterSettings}
import org.openrdf.rio.{RDFFormat, Rio, WriterConfig}
import org.openrdf.sail.memory.MemoryStore
import org.slf4j.LoggerFactory
import tela.baseinterfaces.{DataStoreConnection, XMPPSession}
import tela.datastore.DataStoreConnectionImpl._

import scala.collection.JavaConversions._

object DataStoreConnectionImpl {
  private val log = Logger(LoggerFactory.getLogger(getClass))

  def getDataStore(rootDirectory: String, user: String, xmppSession: XMPPSession): DataStoreConnectionImpl = {
    log.info("Retrieving data store for user {}", user)

    //if (rootDirectory.isEmpty)

    val file: File = new File(rootDirectory)
    if (!file.isDirectory) {
      throw new IllegalArgumentException("Directory " + rootDirectory + " not found")
    }

    new DataStoreConnectionImpl(new File(rootDirectory, user), user, xmppSession)
  }
}

class DataStoreConnectionImpl(private val root: File, private val user: String, private val xmppSession: XMPPSession) extends DataStoreConnection {
  private[datastore] val repository = new SailRepository(new MemoryStore(root))
  repository.initialize()
  private[datastore] val connection = repository.getConnection

  override def closeConnection(): Unit = {
    log.info("Closing connection for user {}", user)
    connection.close()
    repository.shutDown()
  }

  override def insertJSON(data: String): Unit = {
    log.info("Request to insert data for user {}", user)
    val rdfParser = Rio.createParser(RDFFormat.JSONLD)

    val model = new LinkedHashModel()
    rdfParser.setRDFHandler(new StatementCollector(model))
    rdfParser.parse(new StringReader(data), null)

    if (model.size > 0)
      connection.remove(asScalaSet(model.subjects).toArray.apply(0), null, null)

    log.info("Number of triples {}", model.size.toString)
    connection.add(model)
  }

  override def publish(uri: String): Unit = {
    log.info("Request to publish {} for user {}", uri, user)
    val statements = connection.getStatements(repository.getValueFactory.createURI(uri), null, null, false)
    val model = Iterations.addAll(statements, new LinkedHashModel())
    val output = new StringWriter

    val writerConfig = new WriterConfig()
    writerConfig.set[java.lang.Boolean](XMLWriterSettings.INCLUDE_XML_PI, false)

    Rio.write(model, output, RDFFormat.RDFXML, writerConfig)
    xmppSession.publish(uri, output.toString)
  }

  override def retrieveJSON(uri: String): String = {
    log.info("Request to retrieve {} for user {}", uri, user)
    val statements = connection.getStatements(repository.getValueFactory.createURI(uri), null, null, false)
    val model = Iterations.addAll(statements, new LinkedHashModel())
    val output = new StringWriter
    Rio.write(model, output, RDFFormat.JSONLD)
    output.toString
  }

  override def retrievePublishedDataAsJSON(publisher: String, uri: String): String = {
    log.info("Request to retrieve {} from {} for user {}", uri, publisher, user)
    val rdfParser = Rio.createParser(RDFFormat.RDFXML)
    val model = new LinkedHashModel()
    rdfParser.setRDFHandler(new StatementCollector(model))
    rdfParser.parse(new StringReader(xmppSession.getPublishedData(publisher, uri)), "")
    val output = new StringWriter
    Rio.write(model, output, RDFFormat.JSONLD)
    output.toString
  }
}
