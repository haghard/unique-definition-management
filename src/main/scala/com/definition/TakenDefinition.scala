package com.definition

import akka.Done
import akka.actor.typed.*
import akka.actor.typed.scaladsl.*
import akka.cluster.sharding.typed.ShardingMessageExtractor
import akka.cluster.sharding.typed.scaladsl.*
import akka.pattern.StatusReply
import akka.persistence.typed.*
import akka.persistence.typed.scaladsl.*

import scala.concurrent.duration.DurationInt
import com.definition.domain.*
import com.definition.api.*
import Implicits.*
import com.definition.domain.Cmd as PbCmd
import com.definition.domain.Event as PbEvent

object TakenDefinition {

  val TypeKey: EntityTypeKey[PbCmd] = EntityTypeKey[PbCmd](name = "tkn-dfn")

  object Extractor {
    def apply(numberOfShards: Int): ShardingMessageExtractor[PbCmd, PbCmd] =
      new ShardingMessageExtractor[PbCmd, PbCmd] {
        override def entityId(cmd: PbCmd): String =
          cmd match {
            case Create(_, definition, _) =>
              math.abs(definition.contentKey.hashCode).toString
            case Update(_, definition, _, _) =>
              math.abs(definition.contentKey.hashCode).toString
            case Release(_, definition, _) =>
              math.abs(definition.contentKey.hashCode).toString
            case Passivate() =>
              throw new Exception(s"Unsupported Passivate()")
          }

        override def shardId(entityId: String): String =
          math.abs(entityId.toInt % numberOfShards).toString

        override def unwrapMessage(cmd: PbCmd): PbCmd = cmd
      }
  }

  def apply(entityCtx: EntityContext[PbCmd], snapshotEveryNEvents: Int = 5): Behavior[PbCmd] =
    Behaviors.setup { implicit ctx =>
      implicit val refResolver: ActorRefResolver = ActorRefResolver(ctx.system)

      val path     = ctx.self.path
      val entityId = path.elements.last.toInt

      EventSourcedBehavior
        .withEnforcedReplies[PbCmd, PbEvent, TakenDefinitionBucketState](
          PersistenceId.ofUniqueId(entityCtx.entityId),
          TakenDefinitionBucketState(),
          (state, cmd) => state.applyCmd(cmd),
          (state, event) => state.applyEvt(event)
        )
        .withTagger(_ => Set(math.abs(entityId % Guardian.numberOfTags).toString))
        .snapshotWhen { case (_, _, sequenceNr) =>
          val ifSnap = sequenceNr % snapshotEveryNEvents == 0
          if (ifSnap)
            ctx.log.info(s"Snapshot {}", sequenceNr)

          ifSnap
        }
        .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = snapshotEveryNEvents, keepNSnapshots = 2))
        .receiveSignal {
          case (state, RecoveryCompleted) =>
            ctx.log.warn(s"★★★ RecoveryCompleted $path - ${state.index.values.take(5).mkString(",")}")
          case (state, SnapshotCompleted(_)) =>
            ctx.log.info(s"★★★ SnapshotCompleted: ${state.index.size}")
          case (state, SnapshotFailed(_, ex)) =>
            ctx.log.error(s"★★★ Saving snapshot $state failed", ex)
          case (_, RecoveryFailed(cause)) =>
            ctx.log.error(s"There is a problem with state recovery $cause", cause)
        }
        .onPersistFailure(
          SupervisorStrategy.restartWithBackoff(minBackoff = 3.seconds, maxBackoff = 10.seconds, randomFactor = 0.3)
        )
    }

  implicit class TakenDefinitionBucketStateOps(val self: TakenDefinitionBucketState) extends AnyVal {
    def applyCmd(
      cmd: PbCmd
    )(implicit ctx: ActorContext[PbCmd], resolver: ActorRefResolver): ReplyEffect[PbEvent, TakenDefinitionBucketState] =
      cmd match {
        case Create(ownerId, definition, replyTo) =>
          ctx.log.info(s"★★★> Create ${definition.name} to $ownerId")
          self.index.get(definition.contentKey) match {
            case Some(currentOwnerId) =>
              Effect
                .none[PbEvent, TakenDefinitionBucketState]
                .thenReply(resolver.resolveActorRef(replyTo)) { _: TakenDefinitionBucketState =>
                  if (currentOwnerId == ownerId) {
                    ctx.log.warn("Ignore duplicate Create")
                    // EventSourcedBehavior.lastSequenceNumber(ctx)
                    StatusReply.success(DefinitionReply(ownerId, DefinitionReply.StatusCode.OK))
                  } else {
                    ctx.log.warn(s"Reserved by $currentOwnerId")
                    StatusReply.success(
                      DefinitionReply(ownerId, DefinitionReply.StatusCode.Reserved)
                    )
                  }
                }
            case None =>
              Effect
                .persist(Acquired(ownerId, definition))
                .thenReply(resolver.resolveActorRef(replyTo)) { state =>
                  ctx.log.info(
                    s"Acquired ${definition.name} to $ownerId  Owners:${self.index.values.take(5).mkString(",")}"
                  )
                  StatusReply.success(
                    DefinitionReply(ownerId, DefinitionReply.StatusCode.OK)
                  )
                }
          }
        case Update(ownerId, definition, prevDefinition, replyTo) =>
          ctx.log.info(s"★★★> Update from ${prevDefinition.name} to ${definition.name}  $ownerId")

          self.index.get(definition.contentKey) match {
            case Some(currentOwnerId) =>
              Effect
                .none[PbEvent, TakenDefinitionBucketState]
                .thenReply(resolver.resolveActorRef(replyTo)) { _: TakenDefinitionBucketState =>
                  if (currentOwnerId == ownerId) {
                    ctx.log.warn("IGNORE: duplicate Update")
                    StatusReply.success(
                      DefinitionReply(ownerId, DefinitionReply.StatusCode.OK)
                    )
                  } else {
                    ctx.log.warn(s"Reserved by $currentOwnerId")
                    StatusReply.success(
                      DefinitionReply(ownerId, DefinitionReply.StatusCode.Reserved)
                    )
                  }
                }

            case None =>
              Effect
                .persist(
                  Acquired(ownerId, definition),
                  ReleaseRequested(ownerId, prevDefinition)
                )
                .thenReply(resolver.resolveActorRef(replyTo)) { _ =>
                  StatusReply.success(
                    DefinitionReply(ownerId, DefinitionReply.StatusCode.OK)
                  )
                }
          }

        case Release(ownerId, prevDefinition, replyTo) =>
          ctx.log.info(s"★★★> Release old_definition  OwnerId:$ownerId")
          self.index.get(prevDefinition.contentKey) match {
            case Some(currentOwnerId) =>
              if (ownerId == currentOwnerId)
                Effect
                  .persist(Released(ownerId, prevDefinition))
                  .thenReply(resolver.resolveActorRef(replyTo)) { _ =>
                    ctx.log.warn(s"Released old_definition for $ownerId")
                    StatusReply.success(Done)
                  }
              else
                Effect
                  .none[PbEvent, TakenDefinitionBucketState]
                  .thenReply(resolver.resolveActorRef(replyTo)) { _: TakenDefinitionBucketState =>
                    ctx.log.warn(s"Failed to release payload: Wrong owner actual: $currentOwnerId expected:$ownerId")
                    StatusReply.success(Done)
                  }

            case None =>
              Effect
                .none[PbEvent, TakenDefinitionBucketState]
                .thenReply(resolver.resolveActorRef(replyTo)) { _: TakenDefinitionBucketState =>
                  ctx.log.warn(s"Failed to release old_payload for $ownerId: Not found")
                  StatusReply.success(Done)
                }
          }

        case Passivate() =>
          Effect
            .none[PbEvent, TakenDefinitionBucketState]
            .thenRun(_ => ctx.log.info(s"Passivated: ${self.index.size}"))
            .thenStop()
            .thenNoReply()
      }

    def applyEvt(event: PbEvent): TakenDefinitionBucketState =
      event match {
        case Acquired(ownerId, payload) =>
          val updated = self.index + (payload.contentKey -> ownerId)
          self.update(_.index := updated)
        case Released(_, payload) =>
          val updated = self.index - payload.contentKey
          self.update(_.index := updated)
        case _: ReleaseRequested =>
          self
      }
  }
}
