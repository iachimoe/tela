package tela.web

import java.io.File
import java.nio.file.{Files, Paths}

import akka.actor.ActorRef
import akka.testkit.TestActor.NoAutoPilot
import akka.testkit.{TestActor, TestActorRef}
import io.netty.handler.codec.http.{ClientCookieEncoder, HttpHeaders, HttpMethod}
import org.junit.Assert._
import org.junit.{Before, Test}
import org.mashupbots.socko.events.HttpResponseStatus
import play.api.libs.json.Json
import tela.web.JSONConversions.TextSearchResult
import tela.web.SessionManager._
import JSONConversions._

class DataHandlerTest extends SockoHandlerTestBase {
  val TestUri = "http://tela/profileInfo"

  @Before def initialize(): Unit = {
    doBasicInitialization()
  }

  @Test def notAuthorizedErrorForUserWithoutSession(): Unit = {
    initialiseTestActorAndProbe(false)

    val event = createHttpRequestEvent(HttpMethod.GET, "/data")
    handler ! event

    assertEquals(HttpResponseStatus.UNAUTHORIZED, event.response.status)
    assertResponseBody("")
  }

  @Test def getData(): Unit = {
    initialiseTestActorAndProbe(true, (sender: ActorRef) => {
      case RetrieveData(TestSessionId, `TestUri`) =>
        sender ! "[]"
        NoAutoPilot
    })

    val event = createHttpRequestEvent(HttpMethod.GET, "/data?" + DataHandler.DataUriParameter + "=" + TestUri,
      Map(HttpHeaders.Names.COOKIE -> ClientCookieEncoder.encode(createCookie(SessionIdCookieName, TestSessionId))))

    handler ! event

    assertResponseBody("[]")
    assertEquals(JsonLdContentType, event.response.contentType.get)
  }

  @Test def requestShouldHaveUriParameter(): Unit = {
    initialiseTestActorAndProbe(true)

    val event = createHttpRequestEvent(HttpMethod.GET, "/data", Map(HttpHeaders.Names.COOKIE -> ClientCookieEncoder.encode(createCookie(SessionIdCookieName, TestSessionId))))

    handler ! event

    assertEquals(HttpResponseStatus.BAD_REQUEST, event.response.status)
    assertResponseBody("")
  }

  @Test def putData(): Unit = {
    var publishedData: String = null

    initialiseTestActorAndProbe(true, (sender: ActorRef) => {
      case PublishData(TestSessionId, dataReceived, TestUri) =>
        publishedData = dataReceived
        NoAutoPilot
    })

    val event = createHttpRequestEvent(HttpMethod.PUT, "/data?" + DataHandler.PublishUriParameter + "=" + TestUri, Map(
      HttpHeaders.Names.COOKIE -> ClientCookieEncoder.encode(createCookie(SessionIdCookieName, TestSessionId)),
      HttpHeaders.Names.CONTENT_TYPE -> JsonLdContentType), "[]")

    handler ! event

    assertEquals(HttpResponseStatus.OK, event.response.status)
    assertResponseBody("")
    assertEquals("[]", publishedData)
  }

  @Test def getDataPublishedByOtherUser(): Unit = {
    val testUri = "http://tela/profileInfo"
    val testPublisher = "publisher"

    initialiseTestActorAndProbe(true, (sender: ActorRef) => {
      case RetrievePublishedData(TestSessionId, `testPublisher`, `testUri`) =>
        sender ! "[]"
        NoAutoPilot
    })

    val event = createHttpRequestEvent(HttpMethod.GET,
      "/data?" + DataHandler.DataUriParameter + "=" + testUri + "&" + DataHandler.PublisherParameter + "=" + testPublisher,
      Map(HttpHeaders.Names.COOKIE -> ClientCookieEncoder.encode(createCookie(SessionIdCookieName, TestSessionId))))

    handler ! event

    assertResponseBody("[]")
    assertEquals(JsonLdContentType, event.response.contentType.get)
  }

  @Test def uploadMediaItem(): Unit = {
    var tempFileLocationReceived: String = null

    initialiseTestActorAndProbe(true, (sender: ActorRef) => {
      case StoreMediaItem(TestSessionId, tempFileLocation, None) =>
        tempFileLocationReceived = tempFileLocation
        NoAutoPilot
    })

    val content = "<html><body>Hello, world</body></html>"
    val event = createHttpRequestEvent(HttpMethod.PUT, "/data", Map(
      HttpHeaders.Names.COOKIE -> ClientCookieEncoder.encode(createCookie(SessionIdCookieName, TestSessionId)),
      HttpHeaders.Names.CONTENT_TYPE -> TextHtmlContentType), content)

    handler ! event

    assertNotNull(tempFileLocationReceived)
    try {
      assertEquals(HttpResponseStatus.OK, event.response.status)
      assertResponseBody("")
      val lines = Files.readAllLines(Paths.get(tempFileLocationReceived))
      assertEquals(1, lines.size())
      assertEquals(content, lines.get(0))
    } finally {
      new File(tempFileLocationReceived).delete()
    }
  }

  @Test def uploadMediaItemWithFilename(): Unit = {
    val filename = "hello.html"

    var tempFileLocationReceived: String = null

    initialiseTestActorAndProbe(true, (sender: ActorRef) => {
      case StoreMediaItem(TestSessionId, tempFileLocation, Some(`filename`)) =>
        tempFileLocationReceived = tempFileLocation
        NoAutoPilot
    })

    val content = "<html><body>Hello, world</body></html>"
    val event = createHttpRequestEvent(HttpMethod.PUT, "/data", Map(
      HttpHeaders.Names.COOKIE -> ClientCookieEncoder.encode(createCookie(SessionIdCookieName, TestSessionId)),
      HttpHeaders.Names.CONTENT_TYPE -> TextHtmlContentType,
      DataHandler.FilenameHTTPHeader -> filename), content)

    handler ! event

    assertNotNull(tempFileLocationReceived)
    try {
      assertEquals(HttpResponseStatus.OK, event.response.status)
      assertResponseBody("")
      val lines = Files.readAllLines(Paths.get(tempFileLocationReceived))
      assertEquals(1, lines.size())
      assertEquals(content, lines.get(0))
    } finally {
      new File(tempFileLocationReceived).delete()
    }
  }

  @Test def downloadMediaItem(): Unit = {
    val testHash = "aaaa"
    val testResponseFile = "web/src/test/data/TestApp/index.html"

    initialiseTestActorAndProbe(true, (sender: ActorRef) => {
      case RetrieveMediaItem(TestSessionId, `testHash`) =>
        sender ! Some(testResponseFile)
        NoAutoPilot
    })

    val event = createHttpRequestEvent(HttpMethod.GET, "/data?" + DataHandler.MediaItemUriParameter + "=" + testHash,
      Map(HttpHeaders.Names.COOKIE -> ClientCookieEncoder.encode(createCookie(SessionIdCookieName, TestSessionId))))

    handler ! event

    assertEquals(HttpResponseStatus.OK, event.response.status)
    assertResponseBody(new String(Files.readAllBytes(Paths.get(testResponseFile))))
  }

  @Test def downloadMediaItem_NotFound(): Unit = {
    val testHash = "aaaa"

    initialiseTestActorAndProbe(true, (sender: ActorRef) => {
      case RetrieveMediaItem(TestSessionId, `testHash`) =>
        sender ! None
        NoAutoPilot
    })

    val event = createHttpRequestEvent(HttpMethod.GET, "/data?" + DataHandler.MediaItemUriParameter + "=" + testHash,
      Map(HttpHeaders.Names.COOKIE -> ClientCookieEncoder.encode(createCookie(SessionIdCookieName, TestSessionId))))

    handler ! event

    assertEquals(HttpResponseStatus.NOT_FOUND, event.response.status)
    assertResponseBody("")
  }

  @Test def sparqlQuery(): Unit = {
    val sampleSparqlQuery = "CONSTRUCT { ?s ?p ?o } WHERE {?s ?p ?o }" //TODO URL encode???

    initialiseTestActorAndProbe(true, (sender: ActorRef) => {
      case SPARQLQuery(TestSessionId, `sampleSparqlQuery`) =>
        sender ! "[]"
        NoAutoPilot
    })

    val event = createHttpRequestEvent(HttpMethod.GET, "/data?" + DataHandler.SPARQLQueryParameter + "=" + sampleSparqlQuery,
      Map(HttpHeaders.Names.COOKIE -> ClientCookieEncoder.encode(createCookie(SessionIdCookieName, TestSessionId))))

    handler ! event

    assertResponseBody("[]")
    assertEquals(JsonLdContentType, event.response.contentType.get)
  }

  @Test def textSearch(): Unit = {
    val query = "blah"

    initialiseTestActorAndProbe(true, (sender: ActorRef) => {
      case TextSearch(TestSessionId, `query`) =>
        sender ! TextSearchResult(List("result1", "result2"))
        NoAutoPilot
    })

    val event = createHttpRequestEvent(HttpMethod.GET, "/data?" + DataHandler.TextQueryParameter + "=" + query,
      Map(HttpHeaders.Names.COOKIE -> ClientCookieEncoder.encode(createCookie(SessionIdCookieName, TestSessionId))))

    handler ! event

    assertResponseBody(Json.obj(TextSearchResultsKey -> Json.arr("result1", "result2")))
    assertEquals(JsonContentType, event.response.contentType.get)
  }

  private def initialiseTestActorAndProbe(shouldReturnUserData: Boolean, expectedCases: ((ActorRef) => PartialFunction[Any, TestActor.AutoPilot])*): Unit = {
    handler = TestActorRef(new DataHandler(sessionManagerProbe.ref))
    initializeTestProbe(shouldReturnUserData, expectedCases: _*)
  }
}
