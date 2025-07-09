/*
package com.definition

import akka.actor.typed.*
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.EntityContext
import akka.persistence.typed.{PersistenceId, RecoveryCompleted, RecoveryFailed, SnapshotFailed}
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import com.definition.TakenDefinitionBucket.TakenDefinitionBucketState
import com.definition.domain.Cmd as PbCmd
import com.definition.domain.Event as PbEvent

import scala.concurrent.duration.DurationInt

object Bucket {

  def apply(entityCtx: EntityContext[PbCmd]): Behavior[PbCmd] =
    Behaviors.setup { implicit ctx =>
      implicit val resolver: ActorRefResolver = ActorRefResolver(ctx.system)

      val path         = ctx.self.path
      val selfShardNum = path.elements.last.toInt

      EventSourcedBehavior
        .withEnforcedReplies[PbCmd, PbEvent, TakenDefinitionBucketState](
          PersistenceId(entityCtx.entityTypeKey.name, entityCtx.entityId),
          BucketState(Map.empty, path.toStringWithoutAddress, selfShardNum),
          (state, cmd) => state.applyCmd(cmd),
          (state, event) => state.applyEvt(event)
        )
        .withTagger(_ => Set(math.abs(selfShardNum % Guardian.numberOfTags).toString))
        .receiveSignal {
          case (state, RecoveryCompleted) =>
            ctx.log.warn(s"RecoveryCompleted $path - ${state.definition2OwnerId.values}")
          case (state, SnapshotFailed(_, ex)) =>
            ctx.log.error(s"Saving snapshot $state failed", ex)
          case (_, RecoveryFailed(cause)) =>
            ctx.log.error(s"There is a problem with state recovery $cause", cause)
        }
        .onPersistFailure(
          SupervisorStrategy.restartWithBackoff(minBackoff = 3.seconds, maxBackoff = 10.seconds, randomFactor = 0.3)
        )
    }

  case class BucketState()

}
 */
