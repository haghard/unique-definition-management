package com.definition

import akka.Done
import akka.actor.typed.*
import akka.actor.typed.scaladsl.*
import akka.cluster.sharding.typed.ShardingMessageExtractor
import akka.cluster.sharding.typed.scaladsl.*
import akka.pattern.StatusReply
import akka.persistence.typed.*
import akka.persistence.typed.scaladsl.*
//import com.google.common.collect.*

import scala.concurrent.duration.DurationInt
import com.definition.domain.*
import com.definition.api.*
import Implicits.*
import com.definition.domain.Cmd as PbCmd
import com.definition.domain.Event as PbEvent

object TakenDefinitionBucket {
  type DefinitionContentKey = String
  type OwnerId              = String

  val TypeKey: EntityTypeKey[PbCmd] = EntityTypeKey[PbCmd](name = "taken-definition")

  val NumOfShards  = 512  // 128 ... 2048
  val maxBucketNum = 2048 // should not change
  // each shard hosts 4 entities

  object Extractor {
    def apply(): ShardingMessageExtractor[PbCmd, PbCmd] =
      new ShardingMessageExtractor[PbCmd, PbCmd] {
        override def entityId(cmd: PbCmd): String =
          cmd match {
            case Create(_, definition, _) =>
              math.abs(definition.contentKey.hashCode % maxBucketNum).toString
            case Update(_, definition, _, _) =>
              math.abs(definition.contentKey.hashCode % maxBucketNum).toString
            case Release(_, definition, _) =>
              math.abs(definition.contentKey.hashCode % maxBucketNum).toString
            case Passivate() =>
              throw new Exception(s"Unsupported Passivate()")
          }

        override def shardId(entityId: String): String =
          math.abs(entityId.toInt % NumOfShards).toString

        override def unwrapMessage(cmd: PbCmd): PbCmd = cmd
      }
  }

  def apply(entityCtx: EntityContext[PbCmd]): Behavior[PbCmd] =
    Behaviors.setup { implicit ctx =>
      implicit val resolver: ActorRefResolver = ActorRefResolver(ctx.system)

      val path         = ctx.self.path
      val selfShardNum = path.elements.last.toInt

      EventSourcedBehavior
        .withEnforcedReplies[PbCmd, PbEvent, TakenDefinitionBucketState](
          // PersistenceId(entityCtx.entityTypeKey.name, entityCtx.entityId),
          PersistenceId.ofUniqueId(entityCtx.entityId),
          TakenDefinitionBucketState(Map.empty, path.toStringWithoutAddress),
          (state, cmd) => state.applyCmd(cmd),
          (state, event) => state.applyEvt(event)
        )
        .withTagger(_ => Set(math.abs(selfShardNum % Guardian.numberOfTags).toString))
        .receiveSignal {
          case (state, RecoveryCompleted) =>
            ctx.log.warn(s"RecoveryCompleted $path - ${state.definition2OwnerId.values.take(5).mkString(",")}")
          case (state, SnapshotFailed(_, ex)) =>
            ctx.log.error(s"Saving snapshot $state failed", ex)
          case (_, RecoveryFailed(cause)) =>
            ctx.log.error(s"There is a problem with state recovery $cause", cause)
        }
        .onPersistFailure(
          SupervisorStrategy.restartWithBackoff(minBackoff = 3.seconds, maxBackoff = 10.seconds, randomFactor = 0.3)
        )
    }

  final case class TakenDefinitionBucketState(
    definition2OwnerId: Map[DefinitionContentKey, OwnerId] = Map.empty[DefinitionContentKey, OwnerId],
    // definition2OwnerId: BiMap[DefinitionContentKey, OwnerId], HashBiMap.create()
    path: String
  ) {
    self =>

    def applyCmd(
      cmd: PbCmd
    )(implicit
      ctx: ActorContext[PbCmd],
      resolver: ActorRefResolver
    ): ReplyEffect[PbEvent, TakenDefinitionBucketState] = {

      // If you need to go deeper
      // ctx.spawnAnonymous(Bucket(1))

      val log = ctx.log
      cmd match {
        case Create(ownerId, definition, replyTo) =>
          log.info(s"$path ---> Create ${definition.name} to $ownerId")

          definition2OwnerId.get(definition.contentKey) match {
            case Some(currentOwnerId) =>
              Effect
                .none[PbEvent, TakenDefinitionBucketState]
                .thenReply(resolver.resolveActorRef(replyTo)) { _: TakenDefinitionBucketState =>
                  if (currentOwnerId == ownerId) {
                    log.warn("Ignore duplicate Create")
                    StatusReply.success(DefinitionReply(ownerId, DefinitionReply.StatusCode.OK))
                  } else {
                    log.warn(s"Reserved by $currentOwnerId")
                    StatusReply.success(DefinitionReply(ownerId, DefinitionReply.StatusCode.Reserved))
                  }
                }
            case None =>
              Effect
                .persist(Acquired(ownerId, definition))
                .thenReply(resolver.resolveActorRef(replyTo)) { state =>
                  log.info(
                    s"Acquired ${definition.name} to $ownerId  Owners:${state.definition2OwnerId.values.take(5).mkString(",")}"
                  )
                  StatusReply.success(DefinitionReply(ownerId, DefinitionReply.StatusCode.OK))
                }
          }

        case Update(ownerId, newDefinition, prevDefinition, replyTo) =>
          log.info(s"$path ---> Update from ${prevDefinition.name} to ${newDefinition.name}  $ownerId")

          definition2OwnerId.get(newDefinition.contentKey) match {
            case Some(currentOwnerId) =>
              Effect
                .none[PbEvent, TakenDefinitionBucketState]
                .thenReply(resolver.resolveActorRef(replyTo)) { _: TakenDefinitionBucketState =>
                  if (currentOwnerId == ownerId) {
                    log.warn("IGNORE: duplicate Update")
                    StatusReply.success(DefinitionReply(ownerId, DefinitionReply.StatusCode.OK))
                  } else {
                    log.warn(s"Reserved by $currentOwnerId")
                    StatusReply.success(DefinitionReply(ownerId, DefinitionReply.StatusCode.Reserved))
                  }
                }

            case None =>
              Effect
                .persist(
                  Acquired(ownerId, newDefinition),
                  ReleaseRequested(ownerId, prevDefinition)
                )
                .thenReply(resolver.resolveActorRef(replyTo)) { _ =>
                  StatusReply.success(DefinitionReply(ownerId, DefinitionReply.StatusCode.OK))
                }
          }

        case Release(ownerId, definition, replyTo) =>
          log.info(s"$path  ---> Release old_definition for $ownerId")
          definition2OwnerId.get(definition.contentKey) match {
            case Some(currentOwnerId) =>
              if (ownerId == currentOwnerId)
                Effect
                  .persist(Released(ownerId, definition))
                  .thenReply(resolver.resolveActorRef(replyTo)) { _ =>
                    log.warn(s"Released old_definition for $ownerId")
                    StatusReply.success(Done)
                  }
              else
                Effect
                  .none[PbEvent, TakenDefinitionBucketState]
                  .thenReply(resolver.resolveActorRef(replyTo)) { _: TakenDefinitionBucketState =>
                    log.warn(s"Failed to release payload: Wrong owner actual: $currentOwnerId expected:$ownerId")
                    StatusReply.success(Done)
                  }

            case None =>
              Effect
                .none[PbEvent, TakenDefinitionBucketState]
                .thenReply(resolver.resolveActorRef(replyTo)) { _: TakenDefinitionBucketState =>
                  log.warn(s"Failed to release old_payload for $ownerId: Not found")
                  StatusReply.success(Done)
                }
          }

        case Passivate() =>
          Effect
            .none[PbEvent, TakenDefinitionBucketState]
            .thenRun(_ => log.info(s"Passivated: [$path Size:${definition2OwnerId.size}]"))
            .thenStop()
            .thenNoReply()
      }
    }

    def applyEvt(event: PbEvent): TakenDefinitionBucketState =
      event match {
        case Acquired(ownerId, payload) =>
          // definition2OwnerId.forcePut(payload.contentKey, ownerId)
          self.copy(definition2OwnerId.updated(payload.contentKey, ownerId))
        case Released(_, payload) =>
          self.copy(definition2OwnerId - payload.contentKey)
        case _: ReleaseRequested =>
          self
      }
  }
}
