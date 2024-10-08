package tela.web

import org.apache.pekko.actor.{ActorRef, ActorSystem, FSM}
import org.apache.pekko.testkit.TestActor.{AutoPilot, KeepRunning, NoAutoPilot}
import org.apache.pekko.testkit.{TestActor, TestProbe}
import org.mockito.Mockito._
import controllers.AssetsFinder
import play.api.http.{HeaderNames, Status}
import play.api.mvc.{BodyParsers, ControllerComponents}
import play.api.test.{EssentialActionCaller, Helpers, ResultExtractors, Writeables}
import tela.web.SessionManager.GetSession

class SessionManagerClientBaseSpec extends WebBaseSpec with ResultExtractors with HeaderNames with Status with EssentialActionCaller with Writeables {
  protected class TestEnvironment[T](val client: T, val sessionManagerProbe: TestProbe) {
    def configureTestProbe(expectedCases: ActorRef => PartialFunction[Any, TestActor.AutoPilot] = _ => FSM.NullFunction): Unit = {
      sessionManagerProbe.setAutoPilot(new TestActor.AutoPilot {
        def run(sender: ActorRef, msg: Any): AutoPilot = {
          expectedCases(sender)(msg)
        }
      })
    }

    def configureTestProbeWithGetSessionHandler(shouldReturnUserData: Boolean, expectedCases: ActorRef => PartialFunction[Any, TestActor.AutoPilot] = _ => FSM.NullFunction): Unit = {
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

  protected def controllerComponents(): ControllerComponents = Helpers.stubControllerComponents()

  protected def bodyParser(components: ControllerComponents): BodyParsers.Default = new BodyParsers.Default(components.parsers)

  protected def createMockAssetsFinder(): AssetsFinder = {
    val assetsFinder = mock[AssetsFinder]
    when(assetsFinder.path(BootstrapCssFileName)).thenReturn(BootstrapTestAssetPath)
    when(assetsFinder.path(FontAwesomeFileName)).thenReturn(FontAwesomeTestAssetPath)
    when(assetsFinder.path(TextEditorJsFileName)).thenReturn(TextEditorTestAssetPath)
    assetsFinder
  }
}
