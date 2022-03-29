package tela.datastore

import java.io.{BufferedInputStream, ByteArrayInputStream, StringReader, StringWriter}
import java.net.URI
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.{Formatter, Locale, UUID}
import com.typesafe.scalalogging.Logger
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Field.Store
import org.apache.lucene.document.{Document, StringField, TextField}
import org.apache.lucene.index.{DirectoryReader, IndexWriter, IndexWriterConfig}
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.store.FSDirectory
import org.apache.tika.config.TikaConfig
import org.apache.tika.metadata.{Metadata, TikaCoreProperties}
import org.apache.tika.parser.{AutoDetectParser, ParseContext}
import org.apache.tika.sax.BodyContentHandler
import org.eclipse.rdf4j.common.iteration.Iterations
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.query.{QueryLanguage, QueryResults}
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.rio.helpers.{StatementCollector, XMLWriterSettings}
import org.eclipse.rdf4j.rio.{RDFFormat, Rio, WriterConfig}
import org.eclipse.rdf4j.sail.lucene.LuceneSail
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.slf4j.LoggerFactory
import tela.baseinterfaces.{ComplexObject, DataStoreConnection, XMPPSession}
import tela.datastore.DataStoreConnectionImpl._

import scala.jdk.CollectionConverters._

object DataStoreConnectionImpl {
  private[datastore] val MediaItemsFolderName = "mediaItems"
  private[datastore] val URNBaseForUUIDs = "urn:telaUUID:"

  private val LuceneIndexName = "lucene"
  private val LuceneMaxSearchResults = 100000
  private val LuceneFileNameField = "name"
  private val LuceneFileContentField = "content"

  private val log = Logger(LoggerFactory.getLogger(getClass))

  def getDataStore(rootDirectory: Path, user: String, genericFileDataMap: ComplexObject, dataMapping: Map[String, ComplexObject],
                   xmppSession: XMPPSession, tikaConfigFile: Path,
                   generateUUIDForMediaObjects: () => UUID): DataStoreConnectionImpl = {
    log.info("Retrieving data store for user {}", user)

    if (!rootDirectory.toFile.isDirectory) {
      throw new IllegalArgumentException(s"Directory $rootDirectory not found")
    }

    new DataStoreConnectionImpl(rootDirectory.resolve(user), user, genericFileDataMap, dataMapping, xmppSession, tikaConfigFile, generateUUIDForMediaObjects)
  }

  private[datastore] def convertRDFModelToJson(model: Model): String = {
    val output = new StringWriter
    Rio.write(model, output, RDFFormat.JSONLD)
    output.toString
  }

  private[datastore] def convertDataToRDFModel(data: String, format: RDFFormat): LinkedHashModel = {
    val rdfParser = Rio.createParser(format)
    val model = new LinkedHashModel()
    rdfParser.setRDFHandler(new StatementCollector(model))
    rdfParser.parse(new StringReader(data), "")
    model
  }

  private def writeDocumentToIndex(luceneIndex: FSDirectory, document: Option[Document]): Unit = {
    val luceneIndexWriter = new IndexWriter(luceneIndex, new IndexWriterConfig(new StandardAnalyzer()).setCommitOnClose(true))
    try {
      document.foreach(luceneIndexWriter.addDocument)
    } finally {
      luceneIndexWriter.close()
    }
  }
}

//TODO At the moment this class suffers from various performance/concurrency issues
//Will have to be tested with big data volumes/concurrent access and optimised accordingly
class DataStoreConnectionImpl(root: Path, user: String,
                              genericFileDataMap: ComplexObject, dataMapping: Map[String, ComplexObject],
                              xmppSession: XMPPSession, tikaConfigFile: Path,
                              generateUUID: () => UUID) extends DataStoreConnection {
  private val memoryStore: MemoryStore = new MemoryStore(root.toFile)
  private val luceneSail = new LuceneSail()
  luceneSail.setParameter(LuceneSail.INDEX_CLASS_KEY, "org.eclipse.rdf4j.sail.lucene.LuceneIndex")
  luceneSail.setParameter(LuceneSail.LUCENE_RAMDIR_KEY, "true")
  luceneSail.setBaseSail(memoryStore)

  private[datastore] val repository = new SailRepository(luceneSail)
  repository.init()
  private[datastore] val connection = repository.getConnection

  private val luceneIndex = FSDirectory.open(root.resolve(LuceneIndexName))
  if (!DirectoryReader.indexExists(luceneIndex)) {
    // This will create the index
    writeDocumentToIndex(luceneIndex, None)
  }
  private[datastore] var luceneIndexReader: DirectoryReader = DirectoryReader.open(luceneIndex)

  private val metadataMapper = new MetadataMapper(genericFileDataMap, dataMapping)

  override def closeConnection(): Unit = {
    log.info("Closing connection for user {}", user)
    connection.close()
    repository.shutDown()
    luceneIndexReader.close()
  }

  override def insertJSON(data: String): Unit = {
    log.info("Request to insert data for user {}", user)

    val model = convertDataToRDFModel(data, RDFFormat.JSONLD)

    model.subjects().asScala.headOption.foreach(resource => connection.remove(resource, null, null))

    log.info("Number of triples {}", model.size.toString)
    connection.add(model)
  }

  override def publish(uri: URI): Unit = {
    log.info("Request to publish {} for user {}", uri, user)
    val statements = connection.getStatements(repository.getValueFactory.createIRI(uri.toString), null, null, false)
    val model = Iterations.addAll(statements, new LinkedHashModel())
    val output = new StringWriter

    val writerConfig = new WriterConfig()
    writerConfig.set[java.lang.Boolean](XMLWriterSettings.INCLUDE_XML_PI, false)

    Rio.write(model, output, RDFFormat.RDFXML, writerConfig)
    xmppSession.publish(uri, output.toString)
  }

  override def retrieveJSON(uri: URI): String = {
    log.info("Request to retrieve {} for user {}", uri, user)
    runSPARQLQuery(s"DESCRIBE <$uri>")
  }

  override def retrievePublishedDataAsJSON(publisher: String, uri: URI): String = {
    log.info("Request to retrieve {} from {} for user {}", uri, publisher, user)
    val data = xmppSession.getPublishedData(publisher, uri)
    val model = convertDataToRDFModel(data, RDFFormat.RDFXML)
    convertRDFModelToJson(model)
  }

  override def storeMediaItem(tempFileLocation: Path, originalFileName: Path): Unit = {
    log.info("Request to store file at location {} for user {}", tempFileLocation, user)
    val fileContentAsByteArray = try {
      Files.readAllBytes(tempFileLocation)
    } finally {
      //TODO no longer necessary - play deletes temp file
      tempFileLocation.toFile.delete()
    }
    val hash = calculateHashForFileContent(fileContentAsByteArray)

    storeFileContent(fileContentAsByteArray, hash)

    storeMetadataAndIndexText(originalFileName, fileContentAsByteArray, hash)
  }

  override def retrieveMediaItem(hash: String): Option[Path] = {
    log.info("User {} requesting to retrieve file with hash {}", user, hash)
    val mediaItemsFolder = root.resolve(MediaItemsFolderName)
    val requestedFile = mediaItemsFolder.resolve(hash)
    if (Files.exists(requestedFile) && requestedFile.getParent == mediaItemsFolder) Some(requestedFile) else None
  }

  override def runSPARQLQuery(query: String): String = {
    convertRDFModelToJson(QueryResults.asModel(connection.prepareGraphQuery(QueryLanguage.SPARQL, query).evaluate()))
  }

  override def textSearch(textToFind: String): Vector[String] = {
    //TODO consider using SearcherManager (which can be refreshed via a scheduled akka message) instead of this non-threadsafe process...
    val newReader = DirectoryReader.openIfChanged(luceneIndexReader)
    if (newReader != null) {
      luceneIndexReader.close() //TODO There is no unit test to ensure that the old reader gets closed
      luceneIndexReader = newReader
    }

    val searcher = new IndexSearcher(luceneIndexReader)

    val query = new QueryParser(LuceneFileContentField, new StandardAnalyzer()).parse(textToFind)
    val searchResult = searcher.search(query, LuceneMaxSearchResults)
    searchResult.scoreDocs.toVector.map(result => searcher.doc(result.doc).getField(LuceneFileNameField).stringValue())
  }

  private def storeFileContent(fileContentAsByteArray: Array[Byte], hash: String): Unit = {
    val storeLocation = root.resolve(MediaItemsFolderName)
    if (!Files.exists(storeLocation))
      Files.createDirectory(storeLocation)

    log.info("Storing file with hash {} for user {}", hash, user)
    Files.write(root.resolve(MediaItemsFolderName).resolve(hash), fileContentAsByteArray)
  }

  private def storeMetadataAndIndexText(originalFileName: Path, fileContentAsByteArray: Array[Byte], hash: String): Unit = {
    val handler = new BodyContentHandler(-1)
    val context = new ParseContext()
    val metadata = new Metadata()
    val autoDetectParser = new AutoDetectParser(new TikaConfig(tikaConfigFile))

    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, originalFileName.toString)

    val stream = new BufferedInputStream(new ByteArrayInputStream(fileContentAsByteArray))
    val fileFormat = autoDetectParser.getDetector.detect(stream, metadata).toString
    autoDetectParser.parse(stream, handler, metadata, context)

    connection.add(metadataMapper.convertMetadataToRDF(URNBaseForUUIDs + generateUUID(), fileFormat, metadata.names.map(key => key -> metadata.get(key)).toMap, hash))
    addTextContentToLuceneIndex(hash, handler.toString)
  }

  private def addTextContentToLuceneIndex(filename: String, content: String): Unit = {
    //TODO Maybe we should just store this in rdf4j rather than maintaining a separate Lucene index?
    val document = new Document()
    document.add(new StringField(LuceneFileNameField, filename, Store.YES))
    document.add(new TextField(LuceneFileContentField, content, Store.NO))
    writeDocumentToIndex(luceneIndex, Some(document))
  }

  private def calculateHashForFileContent(allBytes: Array[Byte]): String = {
    val messageDigest = MessageDigest.getInstance("SHA1")
    messageDigest.update(allBytes, 0, allBytes.length)
    val formatter = new Formatter()
    messageDigest.digest().toVector.foreach((b: Byte) => formatter.format(Locale.getDefault, "%02x", b))
    formatter.toString
  }
}
