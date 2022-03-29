package tela.web

import play.api.http.{MimeTypes, Status}
import play.api.test.FakeRequest
import org.scalatest.matchers.should.Matchers._

import scala.concurrent.ExecutionContext.Implicits.global

class StatusControllerSpec extends SessionManagerClientBaseSpec {
  "ping" should "return plain text OK" in {
    val controller = new StatusController(controllerComponents())
    val result = controller.ping().apply(FakeRequest())

    status(result) should === (Status.OK)
    contentType(result) should === (Some(MimeTypes.TEXT))
    contentAsString(result) should === ("OK")
  }
}
