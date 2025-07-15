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
            case Update(_, definition, _, _, _) =>
              math.abs(definition.contentKey.hashCode).toString
            case Release(_, definition, _, _) =>
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
        .withEnforcedReplies[PbCmd, PbEvent, TakenDefinitionState](
          PersistenceId.ofUniqueId(entityCtx.entityId),
          TakenDefinitionState(),
          (state, cmd) => state.applyCmd(cmd, entityId),
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

  implicit class TakenDefinitionStateOps(val pbState: TakenDefinitionState) extends AnyVal {
    def applyCmd(
      cmd: PbCmd,
      entityId: Int
    )(implicit ctx: ActorContext[PbCmd], resolver: ActorRefResolver): ReplyEffect[PbEvent, TakenDefinitionState] =
      cmd match {
        case Create(ownerId, definition, replyTo) =>
          ctx.log.info(s"★★★> Create ${definition.name} to $ownerId")
          pbState.index.get(definition.contentKey) match {
            case Some(metadata) =>
              Effect
                .none[PbEvent, TakenDefinitionState]
                .thenReply(resolver.resolveActorRef(replyTo)) { _: TakenDefinitionState =>
                  if (metadata.ownerId == ownerId) {
                    ctx.log.warn("Ignore duplicate Create")

                    StatusReply.success(
                      DefinitionReply(
                        ownerId,
                        DefinitionReply.StatusCode.OK,
                        DefinitionLocation(entityId, metadata.seqNum)
                      )
                    )
                  } else {
                    ctx.log.warn(s"Already reserved by ${metadata.ownerId}")
                    StatusReply.success(
                      DefinitionReply(
                        ownerId,
                        DefinitionReply.StatusCode.Reserved,
                        DefinitionLocation(entityId, metadata.seqNum)
                      )
                    )
                  }
                }
            case None =>
              Effect
                .persist(Acquired(ownerId, definition))
                .thenReply(resolver.resolveActorRef(replyTo)) { _ =>
                  ctx.log.info(
                    s"Acquired ${definition.name} to $ownerId  Owners:${pbState.index.values.take(3).mkString(",")}"
                  )
                  StatusReply.success(
                    DefinitionReply(
                      ownerId,
                      DefinitionReply.StatusCode.OK,
                      DefinitionLocation(entityId, EventSourcedBehavior.lastSequenceNumber(ctx))
                    )
                  )
                }
          }
        case Update(ownerId, definition, prevDefinition, prevDefinitionLocation, replyTo) =>
          ctx.log.info(s"★★★> Update from ${prevDefinition.name} to ${definition.name}  $ownerId")

          pbState.index.get(definition.contentKey) match {
            case Some(metadata) =>
              Effect
                .none[PbEvent, TakenDefinitionState]
                .thenReply(resolver.resolveActorRef(replyTo)) { _: TakenDefinitionState =>
                  if (metadata.ownerId == ownerId) {
                    ctx.log.warn("IGNORE: duplicate Update")
                    StatusReply.success(
                      DefinitionReply(
                        ownerId,
                        DefinitionReply.StatusCode.OK,
                        DefinitionLocation(entityId, metadata.seqNum)
                      )
                    )
                  } else {
                    ctx.log.warn(s"Reserved by ${metadata.ownerId}")
                    StatusReply.success(
                      DefinitionReply(
                        ownerId,
                        DefinitionReply.StatusCode.Reserved,
                        DefinitionLocation(entityId, metadata.seqNum)
                      )
                    )
                  }
                }

            case None =>
              Effect
                .persist(
                  Acquired(ownerId, definition),
                  ReleaseRequested(ownerId, prevDefinition, prevDefinitionLocation)
                )
                .thenReply(resolver.resolveActorRef(replyTo)) { _ =>
                  StatusReply.success(
                    DefinitionReply(
                      ownerId,
                      DefinitionReply.StatusCode.OK,
                      DefinitionLocation(entityId, EventSourcedBehavior.lastSequenceNumber(ctx) - 1)
                    )
                  )
                }
          }

        case Release(ownerId, prevDefinition, prevDefinitionLocation, replyTo) =>
          ctx.log.info(s"★★★> Release old_definition  OwnerId:$ownerId")
          pbState.index.get(prevDefinition.contentKey) match {
            case Some(metadata) =>
              if (metadata.ownerId == ownerId)
                Effect
                  .persist(Released(ownerId, prevDefinition, prevDefinitionLocation))
                  .thenReply(resolver.resolveActorRef(replyTo)) { _ =>
                    ctx.log.warn(s"Released old_definition for $ownerId")
                    StatusReply.success(Done)
                  }
              else
                Effect
                  .none[PbEvent, TakenDefinitionState]
                  .thenReply(resolver.resolveActorRef(replyTo)) { _: TakenDefinitionState =>
                    ctx.log.warn(
                      s"Failed to release payload: Wrong owner actual: ${metadata.ownerId} expected:$ownerId"
                    )
                    StatusReply.success(Done)
                  }

            case None =>
              Effect
                .none[PbEvent, TakenDefinitionState]
                .thenReply(resolver.resolveActorRef(replyTo)) { _: TakenDefinitionState =>
                  ctx.log.warn(s"Failed to release prev_payload for $ownerId: Not found")
                  StatusReply.success(Done)
                }
          }

        case Passivate() =>
          Effect
            .none[PbEvent, TakenDefinitionState]
            .thenRun(_ => ctx.log.info(s"Passivated: ${pbState.index.size}"))
            .thenStop()
            .thenNoReply()
      }

    def applyEvt(event: PbEvent)(implicit ctx: ActorContext[PbCmd]): TakenDefinitionState =
      event match {
        case Acquired(ownerId, definition) =>
          val md = DefinitionMetadata(ownerId, EventSourcedBehavior.lastSequenceNumber(ctx) + 1)
          val updatedMap = pbState.index + (definition.contentKey -> md)
          pbState.update(_.index := updatedMap)
        case Released(_, definition, _) =>
          val updatedMap = pbState.index - definition.contentKey
          pbState.update(_.index := updatedMap)
        case _: ReleaseRequested =>
          pbState
      }
  }
}
