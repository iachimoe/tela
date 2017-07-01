package tela.web

import akka.actor.{ActorRef, ActorSystem, FSM}
import akka.stream.Materializer
import akka.testkit.TestActor.{AutoPilot, KeepRunning, NoAutoPilot}
import akka.testkit.{TestActor, TestProbe}
import play.api.Application
import play.api.http.{HeaderNames, Status}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.{EssentialActionCaller, ResultExtractors, Writeables}
import tela.web.SessionManager.GetSession

class SessionManagerClientBaseSpec extends WebBaseSpec with ResultExtractors with HeaderNames with Status with EssentialActionCaller with Writeables {
  protected class TestEnvironment[T](val client: T, val sessionManagerProbe: TestProbe) {
    def configureTestProbe(expectedCases: (ActorRef) => PartialFunction[Any, TestActor.AutoPilot] = _ => FSM.NullFunction): Unit = {
      sessionManagerProbe.setAutoPilot(new TestActor.AutoPilot {
        def run(sender: ActorRef, msg: Any): AutoPilot = {
          expectedCases(sender)(msg)
        }
      })
    }

    def configureTestProbeWithGetSessionHandler(shouldReturnUserData: Boolean, expectedCases: (ActorRef) => PartialFunction[Any, TestActor.AutoPilot] = _ => FSM.NullFunction): Unit = {
      configureTestProbe((sender: ActorRef) => {
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

        sessionRetriever orElse expectedCases(sender)
      })
    }
  }

  protected def createTestEnvironment[T](createClient: (ActorRef, ActorSystem) => T): TestEnvironment[T] = {
    implicit val actorSystem = ActorSystem("actor")
    val sessionManagerProbe = TestProbe()
    new TestEnvironment(createClient(sessionManagerProbe.ref, actorSystem), sessionManagerProbe)
  }

  protected def buildMaterializer(): Materializer = buildApplication().materializer

  protected def buildApplication(): Application = new GuiceApplicationBuilder().build()
}
