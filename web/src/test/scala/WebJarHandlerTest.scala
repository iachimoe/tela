package tela.web

import akka.testkit.TestActorRef
import io.netty.handler.codec.http.HttpMethod
import org.junit.Assert._
import org.junit.{Before, Test}
import org.mashupbots.socko.events.HttpResponseStatus

class WebJarHandlerTest extends SockoHandlerTestBase {
  @Before def initialize(): Unit = {
    doBasicInitialization()
  }

  @Test def canRetrieveJavascriptFileFromJar(): Unit = {
    handler = TestActorRef(new WebJarHandler("require.js"))

    val event = createHttpRequestEvent(HttpMethod.GET, "/webjars/" + "require.js")
    handler ! event

    assertEquals(HttpResponseStatus.OK, event.response.status)
    assertResponseBodyStartsWith("/** vim: et:ts=4:sw=4")
  }
}
