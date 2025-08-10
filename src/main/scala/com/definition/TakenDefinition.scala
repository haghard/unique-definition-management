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

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

object TakenDefinition {

  val TypeKey: EntityTypeKey[PbCmd] = EntityTypeKey[PbCmd](name = "tkn-dfn")

  object Extractor {
    def apply(numberOfShards: Int): ShardingMessageExtractor[PbCmd, PbCmd] =
      new ShardingMessageExtractor[PbCmd, PbCmd] {
        override def entityId(cmd: PbCmd): String =
          cmd match {
            case Create(_, definition, _) =>
              val bts = ByteBuffer.wrap(definition.contentKey.getBytes(StandardCharsets.UTF_8))
              CassandraMurmurHash.hash2_64(bts, 0, bts.array.length, akka.util.HashCode.SEED).toString
            case Update(_, definition, _, _) =>
              val bts = ByteBuffer.wrap(definition.contentKey.getBytes(StandardCharsets.UTF_8))
              CassandraMurmurHash.hash2_64(bts, 0, bts.array.length, akka.util.HashCode.SEED).toString
            case Release(_, prevDefinitionLocation, _) =>
              prevDefinitionLocation.entityId.toString
            case Passivate() =>
              throw new Exception(s"Unsupported Passivate()")
          }

        override def shardId(entityId: String): String =
          math.abs(entityId.toLong % numberOfShards).toString

        override def unwrapMessage(cmd: PbCmd): PbCmd = cmd
      }
  }

  def apply(entityCtx: EntityContext[PbCmd], snapshotEveryNEvents: Int = 5): Behavior[PbCmd] =
    Behaviors.setup { implicit ctx =>
      implicit val refResolver: ActorRefResolver = ActorRefResolver(ctx.system)

      val path     = ctx.self.path
      val entityId = path.elements.last.toLong

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
            ctx.log.warn(s"★★★ RecoveryCompleted: - ${state.contentKeySeqNum.size}")
          case (state, SnapshotCompleted(_)) =>
            ctx.log.info(s"★★★ SnapshotCompleted: ${state.contentKeySeqNum.size}")
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
      entityId: Long
    )(implicit ctx: ActorContext[PbCmd], resolver: ActorRefResolver): ReplyEffect[PbEvent, TakenDefinitionState] = {
      val causalToken = System.nanoTime()
      cmd match {
        case Create(ownerId, definition, replyTo) =>
          ctx.log.info(s"★★★> Create ${definition.name} to $ownerId")
          if (pbState.contentKeySeqNum.contains(definition.contentKey)) {
            Effect
              .none[PbEvent, TakenDefinitionState]
              .thenReply(resolver.resolveActorRef(replyTo)) { _: TakenDefinitionState =>
                ctx.log.warn("Already reserved: ConcurrentAccess")
                StatusReply.success(
                  PutReply(
                    ownerId,
                    PutReply.StatusCode.ConcurrentAccess,
                    -1 // Can provide real cToken here,
                  )
                )
              }
          } else {
            val seqNum = EventSourcedBehavior.lastSequenceNumber(ctx) + 1
            Effect
              .persist(Acquired(ownerId, definition, seqNum, causalToken))
              .thenReply(resolver.resolveActorRef(replyTo)) { _ =>
                ctx.log.info(s"OwnerId:$ownerId acquired ${definition.name}")
                StatusReply.success(
                  PutReply(
                    ownerId,
                    PutReply.StatusCode.OK,
                    causalToken
                  )
                )
              }
          }

        case Update(ownerId, definition, prevDefinitionLocation, replyTo) =>
          ctx.log.info(s"★★★> Update ${definition.name}  OwnerId:$ownerId")

          if (pbState.contentKeySeqNum.contains(definition.contentKey)) {
            Effect
              .none[PbEvent, TakenDefinitionState]
              .thenReply(resolver.resolveActorRef(replyTo)) { _: TakenDefinitionState =>
                ctx.log.warn("Already reserved: ConcurrentAccess")
                StatusReply.success(
                  PutReply(
                    ownerId,
                    PutReply.StatusCode.ConcurrentAccess,
                    causalToken
                  )
                )
              }
          } else {
            val seqNum = EventSourcedBehavior.lastSequenceNumber(ctx) + 1
            Effect
              .persist(
                // AcquireAndReleased(ownerId, definition, seqNum, causalToken, prevDefinitionLocation)
                Acquired(ownerId, definition, seqNum, causalToken),
                ReleaseRequested(ownerId, prevDefinitionLocation)
              )
              .thenReply(resolver.resolveActorRef(replyTo)) { _ =>
                StatusReply.success(
                  PutReply(
                    ownerId,
                    PutReply.StatusCode.OK,
                    causalToken
                  )
                )
              }
          }

        case Release(ownerId, prevDefinitionLocation, replyTo) =>
          pbState.contentKeySeqNum.collectFirst {
            case (_, seqNum) if seqNum == prevDefinitionLocation.seqNum => seqNum
          } match {
            case Some(seqNum) =>
              Effect
                .persist(Released(ownerId, prevDefinitionLocation))
                .thenReply(resolver.resolveActorRef(replyTo)) { _ =>
                  ctx.log.warn(s"Released old_definition for $ownerId:$seqNum")
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
            .thenRun(_ => ctx.log.info(s"Passivated: ${pbState.contentKeySeqNum.size}"))
            .thenStop()
            .thenNoReply()
      }
    }

    def applyEvt(event: PbEvent)(implicit ctx: ActorContext[PbCmd]): TakenDefinitionState =
      event match {
        case Acquired(ownerId, definition, seqNum, causalToken) =>
          ctx.log.info("Acquired: {} by {}/{} token: {}", definition.name, ownerId, seqNum, causalToken)

          // TODO: contentKeySeqNum - Use off-heap maps from one-nio
          val updatedIndex = pbState.contentKeySeqNum + (definition.contentKey -> seqNum)
          pbState.update(_.contentKeySeqNum := updatedIndex)
        case Released(ownerId, prevDefinitionLocation) =>
          val definitionContentKey =
            pbState.contentKeySeqNum
              .collectFirst {
                case (contentKey, seqNum) if seqNum == prevDefinitionLocation.seqNum =>
                  contentKey
              }
              .getOrElse("n")

          ctx.log.info("Released:{} by {}/{}", definitionContentKey, ownerId, prevDefinitionLocation.seqNum)
          val updatedIndex = pbState.contentKeySeqNum - definitionContentKey
          pbState.update(_.contentKeySeqNum := updatedIndex)
        case _: ReleaseRequested =>
          pbState
      }
  }
}
