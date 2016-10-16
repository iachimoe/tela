package tela.web

import java.io.{FileReader, StringWriter}

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestActor.{AutoPilot, KeepRunning, NoAutoPilot}
import akka.testkit.{TestActor, TestProbe}
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http._
import org.junit.Assert._
import org.mashupbots.socko.events.{HttpEventConfig, HttpRequestEvent, HttpResponseMessage}
import org.mockito.ArgumentMatcher
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.junit.AssertionsForJUnit
import org.scalatest.mock.MockitoSugar
import play.api.libs.json.{JsObject, Json}
import tela.web.SessionManager.GetSession

import scala.collection.JavaConversions._
import scala.io.Source

class SockoHandlerTestBase extends AssertionsForJUnit with MockitoSugar {
  protected val TestSessionId = "aaaaaaaaa"
  protected val TestHostname = "localhost"
  protected val TestUsername = "myUser"
  private val Protocol = "http://"

  protected var handler: ActorRef = null
  protected var channelHandlerContext: ChannelHandlerContext = null
  implicit protected var actorSystem: ActorSystem = null
  protected var sessionManagerProbe: TestProbe = null

  protected def doBasicInitialization(): Unit = {
    channelHandlerContext = mock[ChannelHandlerContext]
    actorSystem = ActorSystem("actor")
    handler = null
    sessionManagerProbe = TestProbe()
  }

  protected def initializeTestProbe(shouldReturnUserData: Boolean, expectedCases: ((ActorRef) => PartialFunction[Any, TestActor.AutoPilot])*): Unit = {
    sessionManagerProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): AutoPilot = {
        val sessionRetriever: PartialFunction[Any, TestActor.AutoPilot] = {
          case GetSession(TestSessionId) =>
            if (shouldReturnUserData) {
              sender ! Some(UserData(TestUsername, DefaultLanguage))
              KeepRunning
            }
            else {
              sender ! None
              NoAutoPilot
            }
        }

        val allExpectedCases = sessionRetriever :: expectedCases.map(_(sender)).toList
        allExpectedCases.filter(_.isDefinedAt(msg))(0).apply(msg)
      }
    })
  }

  protected def createHttpRequestEvent(httpMethod: HttpMethod, requestPath: String, headers: Map[String, String] = Map(), content: String = ""): HttpRequestEvent = {
    val httpRequest: DefaultFullHttpRequest = createHttpRequest(httpMethod, requestPath, headers, content)
    HttpRequestEvent(channelHandlerContext, httpRequest, HttpEventConfig(TestHostname, 0, 0, List[String](), None))
  }

  protected def createHttpRequest(httpMethod: HttpMethod, requestPath: String, headers: Map[String, String], content: String = ""): DefaultFullHttpRequest = {
    val httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, httpMethod, Protocol + TestHostname + requestPath, Unpooled.copiedBuffer(content.getBytes))
    httpRequest.headers.add(HttpHeaders.Names.HOST, TestHostname)
    for ((name, value) <- headers) httpRequest.headers.add(name, value)
    httpRequest
  }

  protected def createCookie(name: String, value: String, maxAge: Long = MainPageHandler.CookieExpiresWhenBrowserCloses): Cookie = {
    val cookie = new DefaultCookie(SessionIdCookieName, TestSessionId)
    cookie.setMaxAge(maxAge)
    cookie
  }

  protected def assertResponseContent(response: HttpResponseMessage, template: String, contentRoot: String, templateMappings: Map[String, String]*): Unit = {
    val mustache = (new NonEscapingMustacheFactory).compile(new FileReader(contentRoot + "/" + template), "")
    val writer = new StringWriter
    mustache.execute(writer, templateMappings.map(mapAsJavaMap).toArray[Object])
    assertResponseContent(response, writer.toString)
  }

  protected def assertResponseContent(response: HttpResponseMessage, expected: String): Unit = {
    assertResponseBody(expected)
    assertEquals(TextHtmlContentType, response.contentType.get)
  }

  protected def getMappingsForLanguage(contentFolder: String, lang: String): Map[String, String] = {
    Json.parse(Source.fromFile(contentFolder + "/" + LanguagesFolder + "/" + lang + LanguageFileExtension).mkString).as[Map[String, String]]
  }

  protected def assertResponseBody(expected: String): Unit = {
    assertResponseBody(new HttpResponseBodyMatcher(expected))
  }

  protected def assertResponseBodyStartsWith(expected: String): Unit = {
    assertResponseBody(new HttpResponseBodyStartsWithMatcher(expected))
  }

  private def assertResponseBody(matcher: ArgumentMatcher[DefaultFullHttpResponse]): Unit = {
    verify(channelHandlerContext).writeAndFlush(argThat(matcher))
    assertTrue(handler.isTerminated)
  }

  protected def assertResponseBody(expected: JsObject): Unit = {
    assertResponseBody(expected.toString())
  }

  private class HttpResponseBodyMatcher(private val expectedBody: String) extends ArgumentMatcher[DefaultFullHttpResponse] {
    override def matches(argument: DefaultFullHttpResponse): Boolean = {
      argument match {
        case response: DefaultFullHttpResponse => expectedBody.contentEquals(new String(response.content().array()))
        case _ => false
      }
    }
  }

  private class HttpResponseBodyStartsWithMatcher(private val expectedBody: String) extends ArgumentMatcher[DefaultFullHttpResponse] {
    override def matches(argument: DefaultFullHttpResponse): Boolean = {
      argument match {
        case response: DefaultFullHttpResponse => new String(response.content().array()).startsWith(expectedBody)
        case _ => false
      }
    }
  }
}
