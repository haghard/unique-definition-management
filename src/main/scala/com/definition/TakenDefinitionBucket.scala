/*
package com.definition

import akka.actor.typed.*
import akka.actor.typed.scaladsl.*
import akka.cluster.sharding.typed.ShardingMessageExtractor
import akka.cluster.sharding.typed.scaladsl.*
import akka.persistence.typed.*
import akka.persistence.typed.scaladsl.*
//import com.google.common.collect.*

import scala.concurrent.duration.DurationInt
import com.definition.domain.*
import Implicits.*
import com.definition.domain.Cmd as PbCmd
import com.definition.domain.Event as PbEvent

object TakenDefinitionBucket {

  val TypeKey: EntityTypeKey[PbCmd] = EntityTypeKey[PbCmd](name = "taken-def-bkt")

  // val NumOfShards  = 4 // 128 ... 2048
  // val maxBucketNum = 4 // should not change

  object Extractor {
    def apply(numberOfShards: Int): ShardingMessageExtractor[PbCmd, PbCmd] =
      new ShardingMessageExtractor[PbCmd, PbCmd] {
        override def entityId(cmd: PbCmd): String =
          cmd match {
            case Create(_, definition, _) =>
              math.abs(definition.contentKey.hashCode % numberOfShards).toString
            case Update(_, definition, _, _) =>
              math.abs(definition.contentKey.hashCode % numberOfShards).toString
            case Release(_, definition, _) =>
              math.abs(definition.contentKey.hashCode % numberOfShards).toString
            case Passivate() =>
              throw new Exception(s"Unsupported Passivate()")
          }

        override def shardId(entityId: String): String =
          entityId
        // math.abs(entityId.toInt % NumOfShards).toString

        override def unwrapMessage(cmd: PbCmd): PbCmd = cmd
      }
  }

  def apply(entityCtx: EntityContext[PbCmd]): Behavior[PbCmd] =
    Behaviors.setup { implicit ctx =>
      val path     = ctx.self.path
      val entityId = path.elements.last.toInt

      EventSourcedBehavior
        .withEnforcedReplies[PbCmd, PbEvent, TakenDefinitionBucketState](
          // PersistenceId(entityCtx.entityTypeKey.name, entityCtx.entityId),
          PersistenceId.ofUniqueId(entityCtx.entityId),
          TakenDefinitionBucketState(),
          // TakenDefinitionBucketState(Map.empty, path.toStringWithoutAddress, entityId),
          (state, cmd) => state.applyCmd(cmd),
          (state, event) => state.applyEvt(event)
        )
        .withTagger(_ => Set(math.abs(entityId % Guardian.numberOfTags).toString))
        .receiveSignal {
          case (state, RecoveryCompleted) =>
            ctx.log.warn(s"RecoveryCompleted $path - ${state.index.values.take(5).mkString(",")}")
          case (state, SnapshotFailed(_, ex)) =>
            ctx.log.error(s"Saving snapshot $state failed", ex)
          case (_, RecoveryFailed(cause)) =>
            ctx.log.error(s"There is a problem with state recovery $cause", cause)
        }
        .onPersistFailure(
          SupervisorStrategy.restartWithBackoff(minBackoff = 3.seconds, maxBackoff = 10.seconds, randomFactor = 0.3)
        )
    }

  def mkContentKey(definition: Definition): String =
    math.abs(definition.contentKey.hashCode).toString

  implicit class TakenDefinitionBucketStateOps(val self: TakenDefinitionBucketState) extends AnyVal {
    def applyCmd(
      cmd: PbCmd
    )(implicit ctx: ActorContext[PbCmd]): ReplyEffect[PbEvent, TakenDefinitionBucketState] =
      cmd match {
        case c @ Create(ownerId, definition, replyTo) =>
          val id = mkContentKey(definition)
          Effect
            .none[PbEvent, TakenDefinitionBucketState]
            .thenRun { _ =>
              ctx.child(id).fold(ctx.spawn(Bucket(id), id).tell(c))(a => a.unsafeUpcast[PbCmd].tell(c))
            }
            .thenNoReply()
        case c @ Update(ownerId, newDefinition, prevDefinition, replyTo) =>
          val id = mkContentKey(newDefinition)
          Effect
            .none[PbEvent, TakenDefinitionBucketState]
            .thenRun { _ =>
              // 1_073_741_823 / 2_147_483_647
              ctx.child(id).fold(ctx.spawn(Bucket(id), id).tell(c))(a => a.unsafeUpcast[PbCmd].tell(c))
            }
            .thenNoReply()

        case c @ Release(ownerId, definition, replyTo) =>
          val id = mkContentKey(definition)
          Effect
            .none[PbEvent, TakenDefinitionBucketState]
            .thenRun { _ =>
              ctx.child(id).fold(ctx.spawn(Bucket(id), id).tell(c))(a => a.unsafeUpcast[PbCmd].tell(c))
            }
            .thenNoReply()

        case Passivate() =>
          Effect
            .none[PbEvent, TakenDefinitionBucketState]
            .thenRun(_ => ctx.log.info(s"Passivated: [Size:${self.index.size}]"))
            .thenStop()
            .thenNoReply()
      }

    def applyEvt(event: PbEvent): TakenDefinitionBucketState =
      self

    /*event match {
        case Acquired(ownerId, payload) =>
          // definition2OwnerId.forcePut(payload.contentKey, ownerId)
          val updated = self.index + (payload.contentKey -> ownerId)
          self.update(_.index := updated)
        // self.copy(definition2OwnerId + (payload.contentKey -> ownerId))
        case Released(_, payload) =>
          val updated = self.index - payload.contentKey
          self.update(_.index := updated)
        // self.copy(definition2OwnerId - payload.contentKey)
        case _: ReleaseRequested =>
          self
      }*/

  }
}
 */
