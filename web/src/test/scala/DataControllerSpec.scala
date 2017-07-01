package tela.web

import java.io.File
import java.nio.file.{Files, Paths}

import akka.actor.ActorRef
import akka.testkit.TestActor.NoAutoPilot
import org.scalatest.Matchers._
import play.api.http.{ContentTypes, HeaderNames, Status, Writeable}
import play.api.libs.Files.TemporaryFile
import play.api.libs.json.Json
import play.api.mvc.MultipartFormData.FilePart
import play.api.mvc._
import play.api.test.FakeRequest
import play.api.{Application, Play}
import tela.web.JSONConversions._
import tela.web.SessionManager._

import scala.reflect.classTag

class DataControllerSpec extends SessionManagerClientBaseSpec {
  private def testEnvironment(runTest: (TestEnvironment[DataController]) => Unit): Unit = {
    runTest(createTestEnvironment((sessionManager, _) => new DataController(new UserAction(sessionManager), sessionManager)))
  }

  "retrieveData" should "return unauthorized response for user without session" in testEnvironment { environment =>
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = false)
    val result = environment.client.retrieveData(TestDataObjectUri, publisher = None).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)))

    status(result) should === (Status.UNAUTHORIZED)
  }

  it should "return data from session manager" in testEnvironment { environment =>
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true, (sender: ActorRef) => {
      case RetrieveData(TestSessionId, TestDataObjectUri) =>
        sender ! "[]"
        NoAutoPilot
    })

    val result = environment.client.retrieveData(TestDataObjectUri, publisher = None).apply(
      FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId))
    )

    status(result) should === (Status.OK)
    contentType(result) should === (Some(JsonLdContentType))
    contentAsString(result) should === ("[]")
  }

  it should "retrieve data published by other user" in testEnvironment { environment =>
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true, (sender: ActorRef) => {
      case RetrievePublishedData(TestSessionId, TestContact1, TestDataObjectUri) =>
        sender ! "[]"
        NoAutoPilot
    })

    val result = environment.client.retrieveData(TestDataObjectUri, publisher = Some(TestContact1)).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)))

    status(result) should === (Status.OK)
    contentType(result) should === (Some(JsonLdContentType))
    contentAsString(result) should === ("[]")
  }

  "publishData" should "send data to be published to session manager" in testEnvironment { environment =>
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true, (sender: ActorRef) => {
      case _: PublishData => NoAutoPilot
    })

    implicit val mat = buildMaterializer()

    val result = call(environment.client.publishData(TestDataObjectUri), FakeRequest().
      withCookies(Cookie(SessionIdCookieName, TestSessionId)).
      withJsonBody(Json.arr()).
      withHeaders(CONTENT_TYPE -> JsonLdContentType))

    status(result) should === (Status.OK)
    environment.sessionManagerProbe.expectMsg(GetSession(TestSessionId))
    environment.sessionManagerProbe.expectMsg(PublishData(TestSessionId, "[]", TestDataObjectUri))
  }

  "uploadMediaItem" should "store the uploaded data in a temporary file and instruct the session manager to store it" in testEnvironment { environment =>
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true, (sender: ActorRef) => {
      case _: StoreMediaItem => NoAutoPilot
    })

    val filename = "myFile.txt"
    val content = "<html><body>Hello, world</body></html>"
    val testFile: File = File.createTempFile("tela", null)
    Files.write(Paths.get(testFile.getAbsolutePath), content.getBytes)

    val app: Application = buildApplication()

    Play.start(app)

    try {
      val data = MultipartFormData(Map(), List(FilePart(key = "", filename = filename, contentType = None, ref = TemporaryFile(testFile))), Nil)
      val body = FakeRequest("POST", "/").withCookies(Cookie(SessionIdCookieName, TestSessionId)).withMultipartFormDataBody(data)

      implicit val anyContentAsMultipartFormWritable: Writeable[AnyContentAsMultipartFormData] = {
        MultipartFormDataWritable.singleton.map(_.mdf)
      }
      implicit val mat = app.materializer

      val result = call(environment.client.uploadMediaItem(), body, body.body)

      environment.sessionManagerProbe.expectMsg(GetSession(TestSessionId))
      val storeMediaItemMessage: StoreMediaItem = environment.sessionManagerProbe.expectMsgType(classTag[StoreMediaItem])

      storeMediaItemMessage.sessionId should === (TestSessionId)
      storeMediaItemMessage.originalFileName should === (filename)
      status(result) should === (Status.OK)
      val lines = Files.readAllLines(Paths.get(storeMediaItemMessage.temporaryFileLocation))
      lines.size() should === (1)
      lines.get(0) should === (content)
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

  "downloadMediaItem" should "request the path to the file from the session manager and send the file to the client" in testEnvironment { environment =>
    val testHash = "aaaa"
    val testResponseFile = "web/src/test/data/TestApp/index.html"

    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true, (sender: ActorRef) => {
      case RetrieveMediaItem(TestSessionId, `testHash`) =>
        sender ! Some(testResponseFile)
        NoAutoPilot
    })

    val result = environment.client.downloadMediaItem(testHash).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)))

    status(result) should === (Status.OK)
    contentType(result) should === (None)
    contentAsBytes(result)(GeneralTimeout, buildMaterializer()).toList.toArray should === (Files.readAllBytes(Paths.get(testResponseFile)))
  }

  it should "return a 404 if the requested file is not found" in testEnvironment { environment =>
    val testHash = "aaaa"

    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true, (sender: ActorRef) => {
      case RetrieveMediaItem(TestSessionId, `testHash`) =>
        sender ! None
        NoAutoPilot
    })

    val result = environment.client.downloadMediaItem(testHash).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)))

    status(result) should === (Status.NOT_FOUND)
    contentAsString(result) should === ("")
  }

  "sparqlQuery" should "return the result of the given SPARQL query" in testEnvironment { environment =>
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true, (sender: ActorRef) => {
      case SPARQLQuery(TestSessionId, WildcardSparqlQuery) =>
        sender ! "[]"
        NoAutoPilot
    })

    val result = environment.client.sparqlQuery(WildcardSparqlQuery).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)))

    status(result) should === (Status.OK)
    contentType(result) should === (Some(JsonLdContentType))
    contentAsString(result) should === ("[]")
  }

  "textSearch" should "return the result of the given text search" in testEnvironment { environment =>
    val query = "blah"

    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true, (sender: ActorRef) => {
      case TextSearch(TestSessionId, `query`) =>
        sender ! TextSearchResult(List("result1", "result2"))
        NoAutoPilot
    })

    val result = environment.client.textSearch(query).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)))

    status(result) should === (Status.OK)
    contentType(result) should === (Some(ContentTypes.JSON))
    contentAsJson(result) should === (Json.obj(TextSearchResultsKey -> Json.arr("result1", "result2")))
  }
}
