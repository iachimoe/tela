package tela.web

import java.nio.file.{Files, Paths}

import akka.actor.ActorRef
import akka.stream.Materializer
import akka.testkit.TestActor.NoAutoPilot
import org.scalatest.matchers.should.Matchers._
import play.api.http._
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.Files.{SingletonTemporaryFileCreator, TemporaryFile}
import play.api.libs.json.Json
import play.api.mvc.MultipartFormData.FilePart
import play.api.mvc._
import play.api.test.FakeRequest
import tela.web.JSONConversions._
import tela.web.SessionManager._

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

  "uploadMediaItem" should "store the uploaded data in a temporary file and instruct the session manager to store it" in testEnvironment { (environment, materializer) =>
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
    val body = FakeRequest("POST", "/").withCookies(Cookie(SessionIdCookieName, TestSessionIdAsString)).withMultipartFormDataBody(data)

    implicit val mat: Materializer = materializer
    val result = call(environment.client.uploadMediaItem(), body, body.body)

    environment.sessionManagerProbe.expectMsg(GetSession(TestSessionId))
    val storeMediaItemMessage = environment.sessionManagerProbe.expectMsgType(classTag[StoreMediaItem])

    storeMediaItemMessage.sessionId should === (TestSessionId)
    storeMediaItemMessage.originalFileName should === (Paths.get(filename))
    status(result) should === (Status.OK)
    val lines = Files.readAllLines(storeMediaItemMessage.temporaryFileLocation)
    lines.asScala.toVector should === (Vector(content))
  }

  "downloadMediaItem" should "request the path to the file from the session manager and send the file to the client" in testEnvironment { (environment, materializer) =>
    val testHash = "aaaa"
    val testResponseFile = Paths.get("web/src/test/data/TestApp/index.html")

    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true, (sender: ActorRef) => {
      case RetrieveMediaItem(TestSessionId, `testHash`) =>
        sender ! Some(testResponseFile)
        NoAutoPilot
    })

    val result = environment.client.downloadMediaItem(testHash).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionIdAsString)))

    status(result) should === (Status.OK)
    contentType(result) should === (None)
    contentAsBytes(result)(GeneralTimeout, materializer).toVector.toArray should === (Files.readAllBytes(testResponseFile))
  }

  it should "return a 404 if the requested file is not found" in testEnvironment { (environment, _) =>
    val testHash = "aaaa"

    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true, (sender: ActorRef) => {
      case RetrieveMediaItem(TestSessionId, `testHash`) =>
        sender ! None
        NoAutoPilot
    })

    val result = environment.client.downloadMediaItem(testHash).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionIdAsString)))

    status(result) should === (Status.NOT_FOUND)
    contentAsString(result) should === ("")
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

  "textSearch" should "return the result of the given text search" in testEnvironment { (environment, _) =>
    val query = "blah"

    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true, (sender: ActorRef) => {
      case TextSearch(TestSessionId, `query`) =>
        sender ! TextSearchResult(Vector("result1", "result2"))
        NoAutoPilot
    })

    val result = environment.client.textSearch(query).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionIdAsString)))

    status(result) should === (Status.OK)
    contentType(result) should === (Some(ContentTypes.JSON))
    contentAsJson(result) should === (Json.obj(TextSearchResultsKey -> Json.arr("result1", "result2")))
  }
}
