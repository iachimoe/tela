package tela.web

import java.io.File
import java.nio.file.{Files, Paths}

import akka.actor.ActorRef
import akka.testkit.TestActor.NoAutoPilot
import org.junit.Assert._
import org.junit.{Before, Test}
import play.api.http.{ContentTypes, HeaderNames, Status, Writeable}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.Files.TemporaryFile
import play.api.libs.json.Json
import play.api.mvc.MultipartFormData.FilePart
import play.api.mvc._
import play.api.test.Helpers.{contentAsString, contentType, _}
import play.api.test.{FakeRequest, Helpers}
import play.api.{Application, Play}
import tela.web.JSONConversions.{TextSearchResult, _}
import tela.web.SessionManager._

class DataHandlerTest extends SessionManagerClientTestBase {
  val TestUri = "http://tela/profileInfo"

  @Before def initialize(): Unit = {
    doBasicInitialization()
  }

  @Test def notAuthorizedErrorForUserWithoutSession(): Unit = {
    initializeTestProbe(false)

    val controller = new DataController(new UserAction(sessionManagerProbe.ref), sessionManagerProbe.ref)
    val result = controller.retrieveData(TestUri, publisher = None).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)))

    assertEquals(Status.UNAUTHORIZED, status(result)(tela.web.timeout))
  }

  @Test def retrieveData(): Unit = {
    initializeTestProbe(true, (sender: ActorRef) => {
      case RetrieveData(TestSessionId, `TestUri`) =>
        sender ! "[]"
        NoAutoPilot
    })

    val controller = new DataController(new UserAction(sessionManagerProbe.ref), sessionManagerProbe.ref)
    val result = controller.retrieveData(TestUri, publisher = None).apply(
      FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId))
    )

    assertEquals(Status.OK, status(result)(tela.web.timeout))
    assertEquals(Some(JsonLdContentType), contentType(result)(tela.web.timeout))
    assertEquals("[]", contentAsString(result)(tela.web.timeout))
  }

  @Test def publishData(): Unit = {
    var publishedData: String = null

    initializeTestProbe(true, (sender: ActorRef) => {
      case PublishData(TestSessionId, dataReceived, TestUri) =>
        publishedData = dataReceived
        NoAutoPilot
    })

    val controller = new DataController(new UserAction(sessionManagerProbe.ref), sessionManagerProbe.ref)

    implicit val mat = buildMaterializer()

    val result = Helpers.call(controller.publishData(TestUri), FakeRequest().
      withCookies(Cookie(SessionIdCookieName, TestSessionId)).
      withJsonBody(Json.arr()).
      withHeaders(CONTENT_TYPE -> JsonLdContentType))

    assertEquals(Status.OK, status(result)(tela.web.timeout))
    assertEquals("[]", publishedData)
  }

  @Test def retrieveDataPublishedByOtherUser(): Unit = {
    val testUri = "http://tela/profileInfo"
    val testPublisher = "publisher"

    initializeTestProbe(true, (sender: ActorRef) => {
      case RetrievePublishedData(TestSessionId, `testPublisher`, `testUri`) =>
        sender ! "[]"
        NoAutoPilot
    })

    val controller = new DataController(new UserAction(sessionManagerProbe.ref), sessionManagerProbe.ref)
    val result = controller.retrieveData(TestUri, publisher = Some(testPublisher)).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)))

    assertEquals(Status.OK, status(result)(tela.web.timeout))
    assertEquals(Some(JsonLdContentType), contentType(result)(tela.web.timeout))
    assertEquals("[]", contentAsString(result)(tela.web.timeout))
  }

  @Test def uploadMediaItem(): Unit = {
    var tempFileLocationReceived: String = null
    val filename = "myFile.txt"

    initializeTestProbe(true, (sender: ActorRef) => {
      case StoreMediaItem(TestSessionId, tempFileLocation, `filename`) =>
        tempFileLocationReceived = tempFileLocation
        NoAutoPilot
    })

    val content = "<html><body>Hello, world</body></html>"
    val testFile: File = File.createTempFile("tela", null)
    Files.write(Paths.get(testFile.getAbsolutePath), content.getBytes)

    val controller = new DataController(new UserAction(sessionManagerProbe.ref), sessionManagerProbe.ref)

    val app: Application = new GuiceApplicationBuilder().build()

    Play.start(app)

    try {
      val data = MultipartFormData(Map(), List(FilePart(key = "", filename = filename, contentType = None, ref = TemporaryFile(testFile))), Nil)
      val body = FakeRequest("POST", "/").withCookies(Cookie(SessionIdCookieName, TestSessionId)).withMultipartFormDataBody(data)

      implicit val anyContentAsMultipartFormWritable: Writeable[AnyContentAsMultipartFormData] = {
        MultipartFormDataWritable.singleton.map(_.mdf)
      }
      implicit val mat = app.materializer

      val result = Helpers.call(controller.uploadMediaItem(), body, body.body)

      assertEquals(Status.OK, status(result)(tela.web.timeout))
      val lines = Files.readAllLines(Paths.get(tempFileLocationReceived))
      assertEquals(1, lines.size())
      assertEquals(content, lines.get(0))
    } finally {
      Play.stop(app)
    }
  }

  //This was stolen off the internet. I have no idea how or why it works
  //http://tech.fongmun.com/post/125479939452/test-multipartformdata-in-play
  object MultipartFormDataWritable {
    private val boundary = "--------ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"

    private def formatDataParts(data: Map[String, Seq[String]]) = {
      val dataParts = data.flatMap { case (key, values) =>
        values.map { value =>
          val name = s""""$key""""
          s"--$boundary\r\n${HeaderNames.CONTENT_DISPOSITION}: form-data; name=$name\r\n\r\n$value\r\n"
        }
      }.mkString("")
      Codec.utf_8.encode(dataParts)
    }

    private def filePartHeader(file: FilePart[TemporaryFile]) = {
      val name = s""""${file.key}""""
      val filename = s""""${file.filename}""""
      val contentType = file.contentType.map { ct =>
        s"${HeaderNames.CONTENT_TYPE}: $ct\r\n"
      }.getOrElse("")
      Codec.utf_8.encode(s"--$boundary\r\n${HeaderNames.CONTENT_DISPOSITION}: form-data; name=$name; filename=$filename\r\n$contentType\r\n")
    }

    val singleton = Writeable[MultipartFormData[TemporaryFile]](
      transform = { form: MultipartFormData[TemporaryFile] =>
        formatDataParts(form.dataParts) ++
          form.files.flatMap { file =>
            val fileBytes = Files.readAllBytes(Paths.get(file.ref.file.getAbsolutePath))
            filePartHeader(file) ++ fileBytes ++ Codec.utf_8.encode("\r\n")
          } ++
          Codec.utf_8.encode(s"--$boundary--")
      },
      contentType = Some(s"multipart/form-data; boundary=$boundary")
    )
  }

  @Test def downloadMediaItem(): Unit = {
    val testHash = "aaaa"
    val testResponseFile = "web/src/test/data/TestApp/index.html"

    initializeTestProbe(true, (sender: ActorRef) => {
      case RetrieveMediaItem(TestSessionId, `testHash`) =>
        sender ! Some(testResponseFile)
        NoAutoPilot
    })

    val controller = new DataController(new UserAction(sessionManagerProbe.ref), sessionManagerProbe.ref)

    val result = controller.downloadMediaItem(testHash).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)))

    assertEquals(Status.OK, status(result)(tela.web.timeout))
    assertEquals(None, contentType(result)(tela.web.timeout))
    assertArrayEquals(
      Files.readAllBytes(Paths.get(testResponseFile)),
      contentAsBytes(result)(tela.web.timeout, buildMaterializer()).toList.toArray)
  }

  @Test def downloadMediaItem_NotFound(): Unit = {
    val testHash = "aaaa"

    initializeTestProbe(true, (sender: ActorRef) => {
      case RetrieveMediaItem(TestSessionId, `testHash`) =>
        sender ! None
        NoAutoPilot
    })

    val controller = new DataController(new UserAction(sessionManagerProbe.ref), sessionManagerProbe.ref)

    val result = controller.downloadMediaItem(testHash).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)))

    assertEquals(Status.NOT_FOUND, status(result)(tela.web.timeout))
    assertEquals("", contentAsString(result)(tela.web.timeout))
  }

  @Test def sparqlQuery(): Unit = {
    val sampleSparqlQuery = "CONSTRUCT { ?s ?p ?o } WHERE {?s ?p ?o }"

    initializeTestProbe(true, (sender: ActorRef) => {
      case SPARQLQuery(TestSessionId, `sampleSparqlQuery`) =>
        sender ! "[]"
        NoAutoPilot
    })

    val controller = new DataController(new UserAction(sessionManagerProbe.ref), sessionManagerProbe.ref)
    val result = controller.sparqlQuery(sampleSparqlQuery).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)))

    assertEquals(Status.OK, status(result)(tela.web.timeout))
    assertEquals(Some(JsonLdContentType), contentType(result)(tela.web.timeout))
    assertEquals("[]", contentAsString(result)(tela.web.timeout))
  }

  @Test def textSearch(): Unit = {
    val query = "blah"

    initializeTestProbe(true, (sender: ActorRef) => {
      case TextSearch(TestSessionId, `query`) =>
        sender ! TextSearchResult(List("result1", "result2"))
        NoAutoPilot
    })

    val controller = new DataController(new UserAction(sessionManagerProbe.ref), sessionManagerProbe.ref)
    val result = controller.textSearch(query).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)))

    assertEquals(Status.OK, status(result)(tela.web.timeout))
    assertEquals(Some(ContentTypes.JSON), contentType(result)(tela.web.timeout))
    assertEquals(Json.obj(TextSearchResultsKey -> Json.arr("result1", "result2")), contentAsJson(result)(tela.web.timeout))
  }
}
