package misc

import org.apache.pekko.actor.SupervisorStrategy.{Resume, Stop}
import org.apache.pekko.actor.{ActorInitializationException, ActorKilledException, DeathPactException, OneForOneStrategy, SupervisorStrategy, SupervisorStrategyConfigurator}
import org.slf4j.LoggerFactory

class GuardianSupervisorStrategy extends SupervisorStrategyConfigurator {
  //TODO not sure if this is the best way to log these exceptions
  private val log = LoggerFactory.getLogger(this.getClass)

  override def create(): SupervisorStrategy = OneForOneStrategy()({
    case _: ActorInitializationException => Stop
    case _: ActorKilledException => Stop
    case _: DeathPactException => Stop
    case e: Exception =>
      log.error("Uncaught actor exception", e)
      Resume
  })
}
