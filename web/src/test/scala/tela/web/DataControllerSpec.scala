package tela.web

import java.nio.file.{Files, Path, Paths}
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.stream.Materializer
import org.apache.pekko.testkit.TestActor.NoAutoPilot
import org.scalatest.matchers.should.Matchers._
import play.api.http._
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.Files.{SingletonTemporaryFileCreator, TemporaryFile}
import play.api.libs.json.Json
import play.api.mvc.MultipartFormData.FilePart
import play.api.mvc._
import play.api.test.FakeRequest
import tela.web.SessionManager._

import java.time.LocalDateTime
import scala.jdk.CollectionConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.classTag

class DataControllerSpec extends SessionManagerClientBaseSpec {
  private def testEnvironment(runTest: (TestEnvironment[DataController], Materializer) => Unit): Unit = {
    val app = new GuiceApplicationBuilder().build()
    try {
      implicit val bodyParser: BodyParsers.Default = app.injector.instanceOf[BodyParsers.Default]
      runTest(createTestEnvironment(
        (sessionManager, _) =>
          new DataController(new UserAction(sessionManager), sessionManager, app.injector.instanceOf[ControllerComponents])), app.materializer)
    } finally {
      app.stop()
    }
  }

  "retrieveData" should "return unauthorized response for user without session" in testEnvironment { (environment, _) =>
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = false)
    val result = environment.client.retrieveData(TestDataObjectUri.toString, publisher = None).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionIdAsString)))

    status(result) should === (Status.UNAUTHORIZED)
  }

  it should "return data from session manager" in testEnvironment { (environment, _) =>
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true, (sender: ActorRef) => {
      case RetrieveData(TestSessionId, TestDataObjectUri) =>
        sender ! "[]"
        NoAutoPilot
    })

    val result = environment.client.retrieveData(TestDataObjectUri.toString, publisher = None).apply(
      FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionIdAsString))
    )

    status(result) should === (Status.OK)
    contentType(result) should === (Some(JsonLdContentType))
    contentAsString(result) should === ("[]")
  }

  it should "retrieve data published by other user" in testEnvironment { (environment, _) =>
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true, (sender: ActorRef) => {
      case RetrievePublishedData(TestSessionId, TestContact1, TestDataObjectUri) =>
        sender ! "[]"
        NoAutoPilot
    })

    val result = environment.client.retrieveData(TestDataObjectUri.toString, publisher = Some(TestContact1)).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionIdAsString)))

    status(result) should === (Status.OK)
    contentType(result) should === (Some(JsonLdContentType))
    contentAsString(result) should === ("[]")
  }

  "publishData" should "send data to be published to session manager" in testEnvironment { (environment, materializer) =>
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true, (sender: ActorRef) => {
      case _: PublishData => NoAutoPilot
    })

    implicit val mat: Materializer = materializer

    val result = call(environment.client.publishData(TestDataObjectUri.toString), FakeRequest().
      withCookies(Cookie(SessionIdCookieName, TestSessionIdAsString)).
      withJsonBody(Json.arr()).
      withHeaders(CONTENT_TYPE -> JsonLdContentType))

    status(result) should === (Status.OK)
    environment.sessionManagerProbe.expectMsg(GetSession(TestSessionId))
    environment.sessionManagerProbe.expectMsg(PublishData(TestSessionId, "[]", TestDataObjectUri))
  }

  private def uploadMediaItemAndAssertCorrectHandling(environment: TestEnvironment[DataController],
                                                      materializer: Materializer,
                                                      rawAndParsedLastModified: (String, Option[LocalDateTime])) = {
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true, (sender: ActorRef) => {
      case _: StoreMediaItem => NoAutoPilot
    })

    val filename = "myFile.txt"
    val content = "<html><body>Hello, world</body></html>"
    val testFile = SingletonTemporaryFileCreator.create("tela")
    Files.write(Paths.get(testFile.getAbsolutePath), content.getBytes)

    val data = MultipartFormData[TemporaryFile](
      Map(),
      Vector(FilePart(key = "", filename = filename, contentType = None, ref = testFile)),
      Vector.empty
    )
    val body = FakeRequest("POST", "/").withCookies(Cookie(SessionIdCookieName, TestSessionIdAsString)).withHeaders(
      LAST_MODIFIED -> rawAndParsedLastModified._1).withMultipartFormDataBody(data)

    implicit val mat: Materializer = materializer
    val result = call(environment.client.uploadMediaItem(), body, body.body)

    environment.sessionManagerProbe.expectMsg(GetSession(TestSessionId))
    val storeMediaItemMessage = environment.sessionManagerProbe.expectMsgType(classTag[StoreMediaItem])

    storeMediaItemMessage.sessionId should ===(TestSessionId)
    storeMediaItemMessage.originalFileName should ===(Paths.get(filename))
    storeMediaItemMessage.lastModified should ===(rawAndParsedLastModified._2)
    status(result) should ===(Status.OK)
    val lines = Files.readAllLines(storeMediaItemMessage.temporaryFileLocation)
    lines.asScala.toVector should ===(Vector(content))
  }

  "uploadMediaItem" should "store the uploaded data in a temporary file and instruct the session manager to store it" in testEnvironment { (environment, materializer) =>
    uploadMediaItemAndAssertCorrectHandling(environment, materializer, TestDateInHttpHeaderFormat -> Some(TestDateAsLocalDateTime))
  }

  it should "ignore malformed last modified dates" in testEnvironment { (environment, materializer) =>
    uploadMediaItemAndAssertCorrectHandling(environment, materializer, "This is not a date string" -> None)
  }

  "downloadMediaItem" should "request the path to the file from the session manager and send the file to the client" in testEnvironment { (environment, materializer) =>
    val testResponseFile = Paths.get("web/src/test/data/TestApp/index.html")
    testDownloadMediaItem(Some(testResponseFile), None, Status.OK, Files.readAllBytes(testResponseFile))(environment, materializer)
  }

  it should "return a 404 if the requested file is not found" in testEnvironment { (environment, materializer) =>
    testDownloadMediaItem(None, None, Status.NOT_FOUND, Array())(environment, materializer)
  }

  it should "retrieve file from archive" in testEnvironment { (environment, materializer) =>
    testDownloadMediaItem(Some(Paths.get("web/src/test/data/nestedZip.zip")),
      Some("outerFolder/innerZip.zip/innerFolder/testTextFile.txt"),
      Status.OK,
      Files.readAllBytes(Paths.get("web/src/test/data/testTextFileWithinZip.txt")))(environment, materializer)
  }

  it should "return 404 for invalid path within archive" in testEnvironment { (environment, materializer) =>
    testDownloadMediaItem(Some(Paths.get("web/src/test/data/nestedZip.zip")),
      Some("outerFolder/innerZip.zip/nonExistent/testTextFile.txt"),
      Status.NOT_FOUND,
      Array())(environment, materializer)
  }

  it should "return 404 when path is empty string" in testEnvironment { (environment, materializer) =>
    testDownloadMediaItem(Some(Paths.get("web/src/test/data/nestedZip.zip")),
      Some(""),
      Status.NOT_FOUND,
      Array())(environment, materializer)
  }

  it should "return 404 when parent file is not an archive" in testEnvironment { (environment, materializer) =>
    testDownloadMediaItem(Some(Paths.get("web/src/test/data/TestApp/index.html")),
      Some("outerFolder/innerZip.zip/innerFolder/testTextFile.txt"),
      Status.NOT_FOUND,
      Array())(environment, materializer)
  }

  it should "return 404 when attempting to retrieve a child of a child that is not an archive" in testEnvironment { (environment, materializer) =>
    testDownloadMediaItem(Some(Paths.get("web/src/test/data/nestedZip.zip")),
      Some("outerFolder/innerZip.zip/innerFolder/testTextFile.txt/something"),
      Status.NOT_FOUND,
      Array())(environment, materializer)
  }

  private def testDownloadMediaItem(file: Option[Path], childPath: Option[String], expectedStatusCode: Int, expectedContent: Array[Byte])(environment: TestEnvironment[DataController], materializer: Materializer) = {
    val testHash = "aaaa"

    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true, (sender: ActorRef) => {
      case RetrieveMediaItem(TestSessionId, `testHash`) =>
        sender ! file
        NoAutoPilot
    })

    val downloadEndpoint = childPath.map(child => environment.client.downloadMediaItem(testHash, child)).getOrElse(environment.client.downloadMediaItem(testHash))
    val result = downloadEndpoint.apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionIdAsString)))

    status(result) should ===(expectedStatusCode)
    contentType(result) should ===(None)
    contentAsBytes(result)(GeneralTimeout, materializer).toVector.toArray should ===(expectedContent)
  }

  "sparqlQuery" should "return the result of the given SPARQL query" in testEnvironment { (environment, _) =>
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true, (sender: ActorRef) => {
      case SPARQLQuery(TestSessionId, WildcardSparqlQuery) =>
        sender ! "[]"
        NoAutoPilot
    })

    val result = environment.client.sparqlQuery(WildcardSparqlQuery).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionIdAsString)))

    status(result) should === (Status.OK)
    contentType(result) should === (Some(JsonLdContentType))
    contentAsString(result) should === ("[]")
  }
}
