package tela.datastore

import java.io.{BufferedInputStream, StringReader, StringWriter}
import java.net.URI
import java.nio.file.{Files, Path}
import java.security.{DigestInputStream, MessageDigest}
import java.util.{Formatter, Locale, UUID}
import com.typesafe.scalalogging.Logger
import org.apache.commons.math3.optim.linear.SolutionCallback
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
import org.eclipse.rdf4j.sail.nativerdf.NativeStore
import org.slf4j.LoggerFactory
import tela.baseinterfaces.{ComplexObject, DataStoreConnection, XMPPSession}
import tela.datastore.DataStoreConnectionImpl.*
import tela.datastore.PathsWithinContainer.TikaPathInfo

import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}

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
  private val nativeStore: NativeStore = new NativeStore(root.toFile)
  private val luceneSail = new LuceneSail()
  luceneSail.setParameter(LuceneSail.LUCENE_DIR_KEY, root.resolve(LuceneDirectoryName).toString)
  // Using the EnglishAnalyzer for now for better stemming of English. Should probably be configurable.
  luceneSail.setParameter(LuceneSail.ANALYZER_CLASS_KEY, "org.apache.lucene.analysis.en.EnglishAnalyzer")
  luceneSail.setParameter(LuceneSail.QUERY_ANALYZER_CLASS_KEY, "org.apache.lucene.analysis.en.EnglishAnalyzer")
  luceneSail.setBaseSail(nativeStore)

  private[datastore] val repository = new SailRepository(luceneSail)
  repository.init()
  private[datastore] val connection = repository.getConnection

  private val metadataMapper = new MetadataMapper(genericFileDataMap, dataMapping)

  //TODO This rigmarole with the ServiceLoader is needed because without it the classloader it was defaulting to
  //when running in the play framework (i.e. when actually running tela) could not find the ICalParser.
  //Note that this did not happen with the unit tests, so they will all pass even without this specific ServiceLoader being used.
  //Right now I'm not sure of the best way to fix this. Perhaps we should just specify a ServiceLoader that does what we want in our tika.xml
  //For now, the fact that we are explicitly setting a ServiceLoader here means that any service loader related config
  //in the tika config file will be ignored.
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

  override def storeMediaItem(tempFileLocation: Path, originalFileName: Path, lastModified: Option[LocalDateTime]): Future[Unit] = {
    log.info("Request to store file at location {} for user {}", tempFileLocation, user)

    (for {
      hash <- calculateHashForFileContent(tempFileLocation)
      _ <- storeFileContent(tempFileLocation, hash)
      // We get tika to process the file from the temporary rather than the permanent location,
      // on the grounds that the temporary location is likely on a local filesytem,
      // whereas the permanent location could be, for example, and NFS mount where access would be slower.
      _ <- storeMetadataAndIndexText(originalFileName, tempFileLocation, hash, lastModified)
    } yield {
      log.info("Finished storing file at location {} for user {}", tempFileLocation, user)
      ()
    }).andThen {
      case _ =>
        //TODO should be no longer necessary as play apparently deletes temp file
        tempFileLocation.toFile.delete()
        ()
    }
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

  private def storeFileContent(sourceLocation: Path, hash: String): Future[Unit] = Future {
    val storeLocation = root.resolve(MediaItemsFolderName)
    if (!Files.exists(storeLocation)) {
      Files.createDirectory(storeLocation)
      ()
    }

    log.info("Storing file with hash {} for user {}", hash, user)
    Files.copy(sourceLocation, root.resolve(MediaItemsFolderName).resolve(hash), REPLACE_EXISTING)
    ()
  }

  private def storeMetadataAndIndexText(originalFileName: Path, fileLocation: Path, hash: String, lastModified: Option[LocalDateTime]): Future[Unit] = {
    Future {
      log.info("Extracting metadata for file with hash {}", hash)
      val handler = new RecursiveParserWrapperHandler(new BasicContentHandlerFactory(BasicContentHandlerFactory.HANDLER_TYPE.TEXT, -1))
      val context = new ParseContext()
      val overallMetadata = new Metadata()

      overallMetadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, originalFileName.toString)
      lastModified.foreach(date => overallMetadata.set(TikaCoreProperties.MODIFIED, date.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)))

      val stream = new BufferedInputStream(Files.newInputStream(fileLocation))
      try {
        tikaParser.parse(stream, handler, overallMetadata, context)
      } finally {
        stream.close()
      }
      handler.getMetadataList.asScala.toVector
    }.transformWith {
      case Success(filesMetadata) =>
        log.info("Storing metadata for file with hash {}", hash)
        createMetadataGraphForMultipleFiles(filesMetadata, originalFileName, hash)
      case Failure(e) =>
        log.error(s"Error storing metadata for file with hash $hash", e)
        Future.successful(())
    }
  }

  private def processTikaPathInfo[T](metadata: Metadata, toYield: (String, String) => T): Option[T] = {
    Option(metadata.get(TikaCoreProperties.EMBEDDED_RESOURCE_PATH)).map(embeddedResourcePath => {
      // The tar part of a tarball doesn't have an INTERNAL_PATH
      // In fact INTERNAL_PATH, when it exists, might always be the same as RESOURCE_NAME_KEY
      // but this is not completely clear. Conceptually it makes more sense to use INTERNAL_PATH where possible.
      // The case where neither is present necessitates using the empty string,
      // otherwise emails with attachments but no subject line within mbox files break.
      val internalPath = Option(metadata.get(TikaCoreProperties.INTERNAL_PATH)).
        orElse(Option(metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY))).getOrElse("")

      toYield(internalPath, embeddedResourcePath)
    })
  }

  private def createMetadataGraphForMultipleFiles(filesMetadata: Vector[Metadata], originalFileName: Path, hash: String): Future[Unit] = {
    val pathsInfo = new PathsWithinContainer(filesMetadata.flatMap(metadata =>
      processTikaPathInfo(metadata, (internalPath, embeddedResourcePath) => TikaPathInfo(internalPath, embeddedResourcePath))))

    // If we do too many metadata conversions at once, we can run out of memory with large datasets,
    // whereas inserting to the data store individually for each file's metadata is prohibitvely slow.
    // Groups of 10000 has seemed reasonable in testing, but might be an idea to make this configurable going forward.
    filesMetadata.iterator.grouped(10000).foldLeft(Future.successful(())) { (acc, metadataGroup) =>
      acc.flatMap { _ =>
        Future {
          val metadataGroupAsGraph = new LinkedHashModel()
          metadataGroup.map(metadata => {
            createMetadataGraphForIndividualFile(metadata, pathsInfo, originalFileName, hash)
          }).foreach(metadataGroupAsGraph.addAll)
          val startTime = System.currentTimeMillis()
          connection.add(metadataGroupAsGraph)
          val endTime = System.currentTimeMillis()
          log.info("Insertion into datastore took {} ms", endTime - startTime)
        }
      }
    }
  }

  private def createMetadataGraphForIndividualFile(individualFileMetadata: Metadata, pathsInfo: PathsWithinContainer, originalFileName: Path, hash: String): LinkedHashModel = {
    val maybePathToFile = processTikaPathInfo(individualFileMetadata, (internalPath, embeddedResourcePath) =>
      pathsInfo.getCompletePath(TikaPathInfo(internalPath, embeddedResourcePath)))
    val metadataMap = individualFileMetadata.names.map(key => key -> individualFileMetadata.getValues(key).toVector).toMap

    metadataMapper.convertMetadataToRDF(URNBaseForUUIDs + generateUUID(),
      metadataMap.get(HttpHeaders.CONTENT_TYPE).flatMap(_.headOption),
      metadataMap,
      metadataMap.get(TikaCoreProperties.TIKA_CONTENT.getName).flatMap(_.headOption),
      maybePathToFile.map(path => s"$hash/$path").getOrElse(hash),
      maybePathToFile.map(path => Path.of(path).getFileName.toString).getOrElse(originalFileName.toString))
  }

  private def calculateHashForFileContent(fileLocation: Path): Future[String] = Future {
    val messageDigest = MessageDigest.getInstance("SHA-256")
    val buffer = Array.ofDim[Byte](8192)
    val digestInputStream = new DigestInputStream(Files.newInputStream(fileLocation), messageDigest)
    try {
      while (digestInputStream.read(buffer) != -1) {}
    } finally {
      digestInputStream.close()
    }

    val formatter = new Formatter()
    messageDigest.digest().toVector.foreach((b: Byte) => formatter.format(Locale.getDefault, "%02x", b))
    formatter.toString
  }
}
