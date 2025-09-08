package com.definition

import org.apache.pekko.Done
import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.*
import org.apache.pekko.cluster.sharding.typed.ShardingMessageExtractor
import org.apache.pekko.cluster.sharding.typed.scaladsl.*
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.typed.*
import org.apache.pekko.persistence.typed.scaladsl.*

import scala.concurrent.duration.DurationInt
import com.definition.domain.*
import com.definition.api.*
import Implicits.*
import com.definition.domain.command.*
import com.definition.domain.event.*

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

object TakenDefinition {

  val TypeKey: EntityTypeKey[Cmd] = EntityTypeKey[Cmd](name = "tkn-dfn")

  object Extractor {
    def apply(numberOfShards: Int): ShardingMessageExtractor[Cmd, Cmd] =
      new ShardingMessageExtractor[Cmd, Cmd] {
        override def entityId(cmd: Cmd): String =
          cmd match {
            case Create(_, definition, _) =>
              val bts = ByteBuffer.wrap(definition.contentKey.getBytes(StandardCharsets.UTF_8))
              CassandraMurmurHash.hash2_64(bts, 0, bts.array.length, org.apache.pekko.util.HashCode.SEED).toString
            case Update(_, definition, _, _) =>
              val bts = ByteBuffer.wrap(definition.contentKey.getBytes(StandardCharsets.UTF_8))
              CassandraMurmurHash.hash2_64(bts, 0, bts.array.length, org.apache.pekko.util.HashCode.SEED).toString
            case Replace(_, _, _, _, prevDefinitionLocation, _) =>
              prevDefinitionLocation.bucketId.toString
            case Passivate() =>
              throw new Exception(s"Unsupported Passivate()")
          }

        override def shardId(entityId: String): String =
          math.abs(entityId.toLong % numberOfShards).toString

        override def unwrapMessage(cmd: Cmd): Cmd = cmd
      }
  }

  def apply(entityCtx: EntityContext[Cmd], snapshotEveryNEvents: Int = 5): Behavior[Cmd] =
    Behaviors.setup { implicit ctx =>
      implicit val refResolver: ActorRefResolver = ActorRefResolver(ctx.system)

      val path     = ctx.self.path
      val entityId = path.elements.last.toLong

      EventSourcedBehavior
        .withEnforcedReplies[Cmd, Event, TakenDefinitionState](
          PersistenceId.ofUniqueId(entityCtx.entityId),
          TakenDefinitionState(),
          (state, cmd) => state.applyCmd(cmd, entityId),
          (state, event) => state.applyEvt(event)
        )
        .withEventPublishing(true)
        // .withTagger(_ => Set(math.abs(entityId % Guardian.numberOfTags).toString))
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
      cmd: Cmd,
      entityId: Long
    )(implicit ctx: ActorContext[Cmd], resolver: ActorRefResolver): ReplyEffect[Event, TakenDefinitionState] =
      cmd match {
        case Create(ownerId, definition, replyTo) =>
          ctx.log.info(s"★★★> Create ${definition.name} to $ownerId")
          // Thread.sleep(3_000) // for local testing

          pbState.contentKeySeqNum.get(definition.contentKey) match {
            case Some(seqNum) =>
              Effect
                .none[Event, TakenDefinitionState]
                .thenReply(resolver.resolveActorRef(replyTo)) { _: TakenDefinitionState =>
                  ctx.log.warn("Already reserved")
                  StatusReply.success(
                    PutReply(ownerId, PutReply.StatusCode.Reserved, DefinitionLocation(entityId, seqNum))
                  )
                }
            case None =>
              val nextSeqNum = EventSourcedBehavior.lastSequenceNumber(ctx) + 1
              Effect
                .persist(Acquired(ownerId, definition, nextSeqNum, None))
                .thenReply(resolver.resolveActorRef(replyTo)) { _ =>
                  ctx.log.info(s"OwnerId:$ownerId acquired ${definition.name}")
                  StatusReply.success(
                    PutReply(
                      ownerId,
                      PutReply.StatusCode.OK,
                      DefinitionLocation(entityId, nextSeqNum)
                    )
                  )
                }
          }

        case Update(ownerId, definition, prevDefinitionLocation, replyTo) =>
          ctx.log.info(s"★★★> Update ${definition.name}  OwnerId:$ownerId")
          // Thread.sleep(3_000) // for local testing

          pbState.contentKeySeqNum.get(definition.contentKey) match {
            case Some(seqNum) =>
              Effect
                .none[Event, TakenDefinitionState]
                .thenReply(resolver.resolveActorRef(replyTo)) { _: TakenDefinitionState =>
                  ctx.log.warn("Already reserved")
                  StatusReply.success(
                    PutReply(
                      ownerId,
                      PutReply.StatusCode.Reserved,
                      DefinitionLocation(entityId, seqNum)
                    )
                  )
                }
            case None =>
              val nextSeqNum = EventSourcedBehavior.lastSequenceNumber(ctx) + 1
              Effect
                .persist(
                  Acquired(ownerId, definition, nextSeqNum, Some(prevDefinitionLocation))
                )
                .thenReply(resolver.resolveActorRef(replyTo)) { _ =>
                  StatusReply.success(
                    PutReply(
                      ownerId,
                      PutReply.StatusCode.OK,
                      DefinitionLocation(entityId, nextSeqNum)
                    )
                  )
                }
          }

        case Replace(ownerId, definition, acquiredSeqNum, acquiredBucketId, prevDefinitionLocation, replyTo) =>
          pbState.contentKeySeqNum.collectFirst {
            case (_, seqNum) if seqNum == prevDefinitionLocation.seqNum => seqNum
          } match {
            case Some(seqNum) =>
              Effect
                .persist(Released(ownerId, prevDefinitionLocation, definition, acquiredSeqNum, acquiredBucketId))
                .thenReply(resolver.resolveActorRef(replyTo)) { _ =>
                  ctx.log.warn(s"Released($ownerId:${seqNum})")
                  StatusReply.success(Done)
                }

            case None =>
              Effect
                .none[Event, TakenDefinitionState]
                .thenReply(resolver.resolveActorRef(replyTo)) { _: TakenDefinitionState =>
                  ctx.log.warn(s"Failed to release prev_payload for $ownerId: Not found")
                  StatusReply.success(Done)
                }
          }

        case Passivate() =>
          Effect
            .none[Event, TakenDefinitionState]
            .thenRun(_ => ctx.log.info(s"Passivated: ${pbState.contentKeySeqNum.size}"))
            .thenStop()
            .thenNoReply()
      }

    def applyEvt(event: Event)(implicit ctx: ActorContext[Cmd]): TakenDefinitionState =
      event match {
        case Acquired(ownerId, definition, seqNum, _) =>
          ctx.log.info("Acquired: {} by {}/{}", definition.name, ownerId, seqNum)
          // TODO: contentKeySeqNum - Use off-heap maps from one-nio
          val updatedIndex = pbState.contentKeySeqNum + (definition.contentKey -> seqNum)
          pbState.update(_.contentKeySeqNum := updatedIndex)
        case Released(ownerId, prevDefinitionLocation, _, _, _) =>
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

      }
  }
}
