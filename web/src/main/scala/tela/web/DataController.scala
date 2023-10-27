package tela.web

import java.net.URI
import java.nio.file.{FileSystem, FileSystems, Files, Path, Paths, ProviderNotFoundException}
import akka.actor.ActorRef
import akka.pattern.ask

import javax.inject.{Inject, Named}
import play.api.Logging
import play.api.http.HttpEntity.Streamed
import play.api.libs.Files.TemporaryFile
import play.api.libs.json.JsValue
import play.api.mvc._
import tela.web.SessionManager._

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

class DataController @Inject()(
                                userAction: UserAction,
                                @Named("session-manager") sessionManager: ActorRef,
                                controllerComponents: ControllerComponents)(implicit ec: ExecutionContext) extends AbstractController(controllerComponents) with Logging {
  def publishData(uri: String): Action[JsValue] = userAction.apply(parse.tolerantJson) { implicit request =>
    logger.info(s"User ${request.sessionData.userData.username} publishing uri $uri")
    sessionManager ! PublishData(request.sessionData.sessionId, request.body.toString(), new URI(uri))
    Ok
  }

  def uploadMediaItem(): Action[MultipartFormData[TemporaryFile]] = userAction.apply(parse.multipartFormData) { implicit request =>
    val files = request.body.files.head
    logger.info(s"User ${request.sessionData.userData.username} uploading file ${files.filename}")
    sessionManager ! StoreMediaItem(request.sessionData.sessionId,
      files.ref.path,
      Paths.get(files.filename),
      getDateFromLastModifiedHeader(request))
    Ok
  }

  private def getDateFromLastModifiedHeader(request: UserRequest[MultipartFormData[TemporaryFile]]) =
    request.headers.get(LAST_MODIFIED).flatMap(asString => {
      try {
        Some(LocalDateTime.parse(asString, ResponseHeader.httpDateFormat))
      } catch {
        case _: Throwable => None
      }
    })

  def retrieveData(uri: String, publisher: Option[String]): Action[AnyContent] = userAction.async { implicit request =>
    val sessionId = request.sessionData.sessionId
    val uriObj = new URI(uri)
    val messageToSend = publisher.map(pub => RetrievePublishedData(sessionId, pub, uriObj)).getOrElse(RetrieveData(sessionId, uriObj))

    logger.info(s"User ${request.sessionData.userData.username} requesting uri $uri from publisher $publisher")
    (sessionManager ? messageToSend).mapTo[String].map(result => {
      Ok(result).as(JsonLdContentType)
    })
  }

  def downloadMediaItem(hash: String): Action[AnyContent] = userAction.async { implicit request =>
    logger.info(s"User ${request.sessionData.userData.username} requesting media item $hash")
    (sessionManager ? RetrieveMediaItem(request.sessionData.sessionId, hash)).mapTo[Option[Path]].map {
      case Some(path) => sendPathWithEmptyContentType(path, () => {})
      case None => NotFound
    }
  }

  def downloadMediaItem(hash: String, childPath: String): Action[AnyContent] = userAction.async { implicit request =>
    logger.info(s"User ${request.sessionData.userData.username} requesting path $childPath from media item $hash")
    for {
      maybeFile <- (sessionManager ? RetrieveMediaItem(request.sessionData.sessionId, hash)).mapTo[Option[Path]]
      maybeChild <- maybeFile.map(file => getChildFromArchive(file, childPath)).getOrElse(Future.successful(None))
    } yield maybeChild.map {
      case (path, fileSystems) => sendPathWithEmptyContentType(path, () => closeFileSystems(fileSystems))
    }.getOrElse(NotFound)
  }

  private def sendPathWithEmptyContentType(path: Path, onClose: () => Unit) = {
    //TODO in order for Safari to download audio/video files, the Range header
    //should be supported. Something like the following code would work in principle:
    //RangeResult.ofPath(path, request.headers.get(RANGE), Option(Files.probeContentType(path)))
    //But getting the content type correctly would require registering a FileTypeDetector (tika provides one)
    //or, preferably, to get the content type from our RDF data store. Accordingly, RangeResult should be doable
    //once we have a clean way of getting the file name/content type from the RDF store.

    //Play tends to set the content type to something very generic by default,
    //which prompts the browser to download (save to disk) rather than display the file in the browser
    //By not setting the content type, the browser will do its own checks to see whether it's something that it can render
    val r: Result = Ok.sendPath(path, onClose = onClose)
    r.copy(body = r.body.asInstanceOf[Streamed].copy(contentType = None))
  }

  // Was originally going to do the file extraction in the data layer, but as the returned by FileSystem objects
  // contain a reference to the FileSystem, it seems unwise to be passing them around via Akka,
  // especially if we want to run the data layer in a different JVM in the future, using, for example, Akka cluster.
  private def getChildFromArchive(archive: Path, childPath: String): Future[Option[(Path, Vector[FileSystem])]] = {
    if (childPath.isEmpty) Future.successful(None)
    else Future {
      //TODO Conceivably this could be quite slow, e.g. for a big zip file on an NFS share
      //Consider using different execution context?
      recursivelyGetPathForChild(FileSystems.newFileSystem(archive), Paths.get(childPath), None, Vector.empty)
    } recover {
      case _: ProviderNotFoundException => None
    }
  }

  // This is particularly gnarly, but I don't see a way to simplify it at this moment
  // It would be very nice to have a unit test (or tests) to ensure that the various filesystem objects get closed
  // (both for cases where the file was not found, and where it was)
  private def recursivelyGetPathForChild(fileSystem: FileSystem, childPath: Path, parent: Option[Path], oldFileSystems: Vector[FileSystem]): Option[(Path, Vector[FileSystem])] = {
    if (childPath.getNameCount == 1) {
      getPathForChild(fileSystem, childPath, parent, oldFileSystems)
    } else {
      val firstPart = childPath.subpath(0, 1)
      val secondPart = childPath.subpath(1, 2)
      val allPartsExceptFirst = childPath.subpath(1, childPath.getNameCount)
      val firstPartWithParents = parent.map(_.resolve(firstPart)).getOrElse(firstPart)
      val pathWithinFileSystem = fileSystem.getPath(firstPartWithParents.toString, secondPart.toString)

      if (Files.exists(pathWithinFileSystem)) {
        recursivelyGetPathForChild(fileSystem, allPartsExceptFirst, Some(firstPartWithParents), oldFileSystems)
      } else {
        try {
          val newfs = FileSystems.newFileSystem(fileSystem.getPath(firstPartWithParents.toString))
          recursivelyGetPathForChild(newfs, allPartsExceptFirst, None, fileSystem +: oldFileSystems)
        } catch {
          case _: Throwable =>
            // The most likely cases are FileSystemNotFoundException and ProviderNotFoundException
            // but we want to close the filesystems we opened for any kind of exception
            closeFileSystems(fileSystem +: oldFileSystems)
            None
        }
      }
    }
  }

  private def getPathForChild(fileSystem: FileSystem, childPath: Path, parent: Option[Path], oldFileSystems: Vector[FileSystem]) = {
    val absolutePath = parent.map(_.resolve(childPath)).getOrElse(childPath)
    val pathWithinFileSystem = fileSystem.getPath(absolutePath.toString)
    if (Files.exists(pathWithinFileSystem))
      Some(pathWithinFileSystem, fileSystem +: oldFileSystems)
    else {
      closeFileSystems(fileSystem +: oldFileSystems)
      None
    }
  }

  private def closeFileSystems(fileSystems: Vector[FileSystem]): Unit = {
    fileSystems.foreach(_.close())
  }

  def sparqlQuery(query: String): Action[AnyContent] = userAction.async { implicit request =>
    logger.info(s"User ${request.sessionData.userData.username} running SPARQL query $query")
    (sessionManager ? SPARQLQuery(request.sessionData.sessionId, query)).mapTo[String].map(result => {
      Ok(result).as(JsonLdContentType)
    })
  }
}
