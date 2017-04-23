package tela.web

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestActor.{AutoPilot, KeepRunning, NoAutoPilot}
import akka.testkit.{TestActor, TestProbe}
import org.scalatest.junit.AssertionsForJUnit
import org.scalatest.mock.MockitoSugar
import play.api.inject.guice.GuiceApplicationBuilder
import tela.web.SessionManager.GetSession

class SessionManagerClientTestBase extends AssertionsForJUnit with MockitoSugar {
  protected val TextHtmlContentType: String = "text/html"

  protected val TestSessionId = "aaaaaaaaa"
  protected val TestUsername = "myUser"

  implicit protected var actorSystem: ActorSystem = null
  protected var sessionManagerProbe: TestProbe = null

  protected def doBasicInitialization(): Unit = {
    actorSystem = ActorSystem("actor")
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

  protected def buildMaterializer() = new GuiceApplicationBuilder().build().materializer
}
