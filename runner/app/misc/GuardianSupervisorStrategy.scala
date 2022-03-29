package misc

import akka.actor.SupervisorStrategy.{Resume, Stop}
import akka.actor.{ActorInitializationException, ActorKilledException, DeathPactException, OneForOneStrategy, SupervisorStrategy, SupervisorStrategyConfigurator}

class GuardianSupervisorStrategy extends SupervisorStrategyConfigurator {
  override def create(): SupervisorStrategy = OneForOneStrategy()({
    case _: ActorInitializationException => Stop
    case _: ActorKilledException => Stop
    case _: DeathPactException => Stop
    case _: Exception => Resume
  })
}
