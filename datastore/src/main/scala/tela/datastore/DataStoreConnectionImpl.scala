package tela.datastore

import java.io.{BufferedInputStream, ByteArrayInputStream, StringReader, StringWriter}
import java.net.URI
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.{Formatter, Locale, UUID}
import com.typesafe.scalalogging.Logger
import org.apache.tika.config.{ServiceLoader, TikaConfig}
import org.apache.tika.metadata.{HttpHeaders, Metadata, TikaCoreProperties}
import org.apache.tika.parser.{AutoDetectParser, ParseContext, RecursiveParserWrapper}
import org.apache.tika.sax.{BasicContentHandlerFactory, RecursiveParserWrapperHandler}
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
import tela.datastore.PathsWithinContainer.TikaPathInfo

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

object DataStoreConnectionImpl {
  private[datastore] val MediaItemsFolderName = "mediaItems"
  private[datastore] val URNBaseForUUIDs = "urn:telaUUID:"
  private val LuceneDirectoryName = "lucene"

  private val log = Logger(LoggerFactory.getLogger(getClass))

  def getDataStore(rootDirectory: Path, user: String, genericFileDataMap: ComplexObject, dataMapping: Map[String, ComplexObject],
                   xmppSession: XMPPSession, tikaConfigFile: Path,
                   generateUUIDForMediaObjects: () => UUID,
                   executionContext: ExecutionContext): Future[DataStoreConnectionImpl] = Future {
      log.info("Retrieving data store for user {}", user)

      if (!rootDirectory.toFile.isDirectory) {
        throw new IllegalArgumentException(s"Directory $rootDirectory not found")
      }

      new DataStoreConnectionImpl(rootDirectory.resolve(user), user, genericFileDataMap, dataMapping,
        xmppSession, tikaConfigFile, generateUUIDForMediaObjects, executionContext)
  }(executionContext)

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
}

class DataStoreConnectionImpl(root: Path, user: String,
                              genericFileDataMap: ComplexObject, dataMapping: Map[String, ComplexObject],
                              xmppSession: XMPPSession, tikaConfigFile: Path, generateUUID: () => UUID,
                              executionContext: ExecutionContext) extends DataStoreConnection {
  private implicit val ec: ExecutionContext = executionContext
  private val memoryStore: MemoryStore = new MemoryStore(root.toFile)
  private val luceneSail = new LuceneSail()
  luceneSail.setParameter(LuceneSail.LUCENE_DIR_KEY, root.resolve(LuceneDirectoryName).toString)
  // Using the EnglishAnalyzer for now for better stemming of English. Should probably be configurable.
  luceneSail.setParameter(LuceneSail.ANALYZER_CLASS_KEY, "org.apache.lucene.analysis.en.EnglishAnalyzer")
  luceneSail.setParameter(LuceneSail.QUERY_ANALYZER_CLASS_KEY, "org.apache.lucene.analysis.en.EnglishAnalyzer")
  luceneSail.setBaseSail(memoryStore)

  private[datastore] val repository = new SailRepository(luceneSail)
  repository.init()
  private[datastore] val connection = repository.getConnection

  private val metadataMapper = new MetadataMapper(genericFileDataMap, dataMapping)

  //TODO This rigmarole with the ServiceLoader is needed because without it the classloader it was defaulting to could
  //not find the ICalParser. This means that any service loader related config in the tika config file will be ignored.
  private val tikaParser = new RecursiveParserWrapper(
    new AutoDetectParser(new TikaConfig(tikaConfigFile, new ServiceLoader(this.getClass.getClassLoader))))

  override def closeConnection(): Future[Unit] = Future {
    log.info("Closing connection for user {}", user)
    connection.close()
    repository.shutDown()
  }

  override def insertJSON(data: String): Future[Unit] = Future {
    log.info("Request to insert data for user {}", user)

    val model = convertDataToRDFModel(data, RDFFormat.JSONLD)

    model.subjects().asScala.headOption.foreach(resource => connection.remove(resource, null, null))
    log.info("Number of triples {}", model.size.toString)
    connection.add(model)
  }

  override def publish(uri: URI): Future[Unit] = Future {
    log.info("Request to publish {} for user {}", uri, user)
    val model = QueryResults.asModel(connection.getStatements(repository.getValueFactory.createIRI(uri.toString), null, null, false))
    val output = new StringWriter

    val writerConfig = new WriterConfig()
    writerConfig.set[java.lang.Boolean](XMLWriterSettings.INCLUDE_XML_PI, false)

    Rio.write(model, output, RDFFormat.RDFXML, writerConfig)
    output
  } flatMap(output => xmppSession.publish(uri, output.toString))

  override def retrieveJSON(uri: URI): Future[String] = {
    log.info("Request to retrieve {} for user {}", uri, user)
    runSPARQLQuery(s"DESCRIBE <$uri>")
  }

  override def retrievePublishedDataAsJSON(publisher: String, uri: URI): Future[String] = {
    log.info("Request to retrieve {} from {} for user {}", uri, publisher, user)
    xmppSession.getPublishedData(publisher, uri).map(data => {
      val model = convertDataToRDFModel(data, RDFFormat.RDFXML)
      convertRDFModelToJson(model)
    })
  }

  override def storeMediaItem(tempFileLocation: Path, originalFileName: Path, lastModified: Option[LocalDateTime]): Future[Unit] = Future {
    log.info("Request to store file at location {} for user {}", tempFileLocation, user)
    val fileContentAsByteArray = try {
      Files.readAllBytes(tempFileLocation)
    } finally {
      //TODO no longer necessary - play deletes temp file
      tempFileLocation.toFile.delete()
      ()
    }
    val hash = calculateHashForFileContent(fileContentAsByteArray)

    storeFileContent(fileContentAsByteArray, hash)

    storeMetadataAndIndexText(originalFileName, fileContentAsByteArray, hash, lastModified)
    log.info("Finished storing file at location {} for user {}", tempFileLocation, user)
  }

  override def retrieveMediaItem(hash: String): Future[Option[Path]] = Future {
    log.info("User {} requesting to retrieve file with hash {}", user, hash)
    val mediaItemsFolder = root.resolve(MediaItemsFolderName)
    val requestedFile = mediaItemsFolder.resolve(hash)
    if (Files.exists(requestedFile) && requestedFile.getParent == mediaItemsFolder) Some(requestedFile) else None
  }

  override def runSPARQLQuery(query: String): Future[String] = Future {
    convertRDFModelToJson(QueryResults.asModel(connection.prepareGraphQuery(QueryLanguage.SPARQL, query).evaluate()))
  }

  private def storeFileContent(fileContentAsByteArray: Array[Byte], hash: String): Unit = {
    val storeLocation = root.resolve(MediaItemsFolderName)
    if (!Files.exists(storeLocation)) {
      Files.createDirectory(storeLocation)
      ()
    }

    log.info("Storing file with hash {} for user {}", hash, user)
    Files.write(root.resolve(MediaItemsFolderName).resolve(hash), fileContentAsByteArray)
    ()
  }

  private def storeMetadataAndIndexText(originalFileName: Path, fileContentAsByteArray: Array[Byte], hash: String, lastModified: Option[LocalDateTime]): Unit = {
    log.info("Extracting metadata for file with hash {}", hash)
    val handler = new RecursiveParserWrapperHandler(new BasicContentHandlerFactory(BasicContentHandlerFactory.HANDLER_TYPE.TEXT, -1))
    val context = new ParseContext()
    val overallMetadata = new Metadata()

    overallMetadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, originalFileName.toString)
    lastModified.foreach(date => overallMetadata.set(TikaCoreProperties.MODIFIED, date.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)))

    val stream = new BufferedInputStream(new ByteArrayInputStream(fileContentAsByteArray))
    tikaParser.parse(stream, handler, overallMetadata, context)
    log.info("Storing metadata for file with hash {}", hash)
    connection.add(createMetadataGraphForMultipleFiles(handler.getMetadataList.asScala.toVector, originalFileName, hash))
  }

  private def createMetadataGraphForMultipleFiles(filesMetadata: Vector[Metadata], originalFileName: Path, hash: String) = {
    val pathsInfo = new PathsWithinContainer(for {
      metadata <- filesMetadata
      embeddedRelationshipId <- Option(metadata.get(TikaCoreProperties.EMBEDDED_RELATIONSHIP_ID))
      embeddedResourcePath <- Option(metadata.get(TikaCoreProperties.EMBEDDED_RESOURCE_PATH))
    } yield TikaPathInfo(embeddedRelationshipId, embeddedResourcePath))

    val allFilesMetadata = filesMetadata.map(fileMetadata => createMetadataGraphForIndividualFile(fileMetadata, pathsInfo, originalFileName, hash))
    val allMetadataAsGraph = new LinkedHashModel()
    allFilesMetadata.foreach(allMetadataAsGraph.addAll)
    allMetadataAsGraph
  }

  private def createMetadataGraphForIndividualFile(individualFileMetadata: Metadata, pathsInfo: PathsWithinContainer, originalFileName: Path, hash: String): LinkedHashModel = {
    val metadataMap = individualFileMetadata.names.map(key => key -> individualFileMetadata.get(key)).toMap

    val maybePathToFile = for {
      embeddedRelationshipId <- metadataMap.get(TikaCoreProperties.EMBEDDED_RELATIONSHIP_ID)
      embeddedResourcePath <- metadataMap.get(TikaCoreProperties.EMBEDDED_RESOURCE_PATH.getName)
    } yield pathsInfo.getCompletePath(TikaPathInfo(embeddedRelationshipId, embeddedResourcePath))

    metadataMapper.convertMetadataToRDF(
      URNBaseForUUIDs + generateUUID(),
      metadataMap(HttpHeaders.CONTENT_TYPE),
      metadataMap,
      metadataMap.get(TikaCoreProperties.TIKA_CONTENT.getName),
      maybePathToFile.map(path => s"$hash/$path").getOrElse(hash),
      maybePathToFile.map(path => Path.of(path).getFileName.toString).getOrElse(originalFileName.toString))
  }

  private def calculateHashForFileContent(allBytes: Array[Byte]): String = {
    //TODO Change to SHA-256?
    val messageDigest = MessageDigest.getInstance("SHA1")
    messageDigest.update(allBytes, 0, allBytes.length)
    val formatter = new Formatter()
    messageDigest.digest().toVector.foreach((b: Byte) => formatter.format(Locale.getDefault, "%02x", b))
    formatter.toString
  }
}
