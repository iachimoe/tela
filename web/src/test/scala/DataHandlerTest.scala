package tela.web

import akka.actor.ActorRef
import akka.testkit.TestActor.NoAutoPilot
import akka.testkit.{TestActor, TestActorRef}
import io.netty.handler.codec.http.{ClientCookieEncoder, HttpHeaders, HttpMethod}
import org.junit.Assert._
import org.junit.{Before, Test}
import org.mashupbots.socko.events.HttpResponseStatus
import tela.web.SessionManager.{PublishData, RetrieveData, RetrievePublishedData}

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

  private def initialiseTestActorAndProbe(shouldReturnUserData: Boolean, expectedCases: ((ActorRef) => PartialFunction[Any, TestActor.AutoPilot])*): Unit = {
    handler = TestActorRef(new DataHandler(sessionManagerProbe.ref))
    initializeTestProbe(shouldReturnUserData, expectedCases: _*)
  }
}
