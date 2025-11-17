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
import com.definition.domain.command.*
import com.definition.domain.event.*

object TakenDefinition {

  val Sep                         = "_"
  val TypeKey: EntityTypeKey[Cmd] = EntityTypeKey[Cmd](name = "tkn-dfn")

  object Extractor {

    def apply( /*numberOfShards: Int*/ ): ShardingMessageExtractor[Cmd, Cmd] =
      new ShardingMessageExtractor[Cmd, Cmd] {

        override def entityId(cmd: Cmd): String =
          cmd match {
            case Create(_, definitionLocation, _) =>
              val entityId = definitionLocation.shardId.toString + Sep + definitionLocation.definitionId.toString
              entityId
            case Update(_, definitionLocation, _, _) =>
              definitionLocation.shardId.toString + Sep + definitionLocation.definitionId.toString
            case Replace(_, _, prevDefinitionLocation, _) =>
              prevDefinitionLocation.shardId.toString + Sep + prevDefinitionLocation.definitionId.toString
            case Passivate() =>
              throw new Exception(s"Unsupported Passivate()")
          }

        override def shardId(entityId: String): String = {
          // math.abs(entityId.toLong % numberOfShards).toString
          val shardId = entityId.split(Sep)(0)
          shardId
        }

        override def unwrapMessage(cmd: Cmd): Cmd = cmd
      }
  }

  def apply(entityCtx: EntityContext[Cmd], snapshotEveryNEvents: Int = 5): Behavior[Cmd] =
    Behaviors.setup { implicit ctx =>
      implicit val refResolver: ActorRefResolver = ActorRefResolver(ctx.system)

      val path     = ctx.self.path
      val segments = path.elements.last.split(Sep)

      val shardId  = segments(0).toInt
      val entityId = segments(1).toLong

      EventSourcedBehavior
        .withEnforcedReplies[Cmd, Event, TakenDefinitionState](
          PersistenceId(TypeKey.name, entityCtx.entityId),
          TakenDefinitionState(),
          (state, cmd) => state.applyCmd(cmd),
          (state, event) => state.applyEvt(event)
        )
        .withEventPublishing(true)
        .withTagger(_ => Set(math.abs(entityId.hashCode % Guardian.numberOfTags).toString))
        .snapshotWhen { case (_, _, sequenceNr) =>
          val ifSnap = sequenceNr % snapshotEveryNEvents == 0
          if (ifSnap)
            ctx.log.info(s"Snapshot {}", sequenceNr)

          ifSnap
        }
        .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = snapshotEveryNEvents, keepNSnapshots = 2))
        .receiveSignal {
          case (state, RecoveryCompleted) =>
            ctx.log.warn(s"★★★ RecoveryCompleted: - ${state.ownerId}")
          case (state, SnapshotCompleted(_)) =>
            ctx.log.info(s"★★★ SnapshotCompleted: ${state.ownerId}")
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
      cmd: Cmd
    )(implicit ctx: ActorContext[Cmd], resolver: ActorRefResolver): ReplyEffect[Event, TakenDefinitionState] =
      cmd match {
        case Create(ownerId, location, replyTo) =>
          // ctx.log.info(s"★★★> Create ${location} to $ownerId")
          // Thread.sleep(3_000) // for local testing

          if (pbState.ownerId.nonEmpty)
            Effect
              .none[Event, TakenDefinitionState]
              .thenReply(resolver.resolveActorRef(replyTo)) { _: TakenDefinitionState =>
                ctx.log.warn(s"Already reserved by ${pbState.ownerId.get}")
                StatusReply.success(
                  PutReply(ownerId, PutReply.StatusCode.Reserved, location)
                )
              }
          else
            Effect
              .persist(Acquired(ownerId, location, None))
              .thenReply(resolver.resolveActorRef(replyTo)) { _ =>
                ctx.log.info(s"OwnerId:$ownerId acquired ${location}")
                StatusReply.success(
                  PutReply(
                    ownerId,
                    PutReply.StatusCode.OK,
                    location
                  )
                )
              }

        case Update(ownerId, location, prevLocation, replyTo) =>
          // Thread.sleep(3_000) // for local testing
          // ctx.log.info(s"★★★> Update ${definition.name}  OwnerId:$ownerId")
          ctx.log.info(s"★★★> Update ${location}  OwnerId:$ownerId")
          if (pbState.ownerId.nonEmpty)
            Effect
              .none[Event, TakenDefinitionState]
              .thenReply(resolver.resolveActorRef(replyTo)) { _: TakenDefinitionState =>
                ctx.log.warn(s"Already reserved by ${pbState.ownerId.get}")
                StatusReply.success(
                  PutReply(
                    ownerId,
                    PutReply.StatusCode.Reserved,
                    location
                  )
                )
              }
          else
            Effect
              .persist(Acquired(ownerId, location, Some(prevLocation)))
              .thenReply(resolver.resolveActorRef(replyTo)) { _ =>
                StatusReply.success(
                  PutReply(
                    ownerId,
                    PutReply.StatusCode.OK,
                    location
                  )
                )
              }

        case Replace(ownerId, newLocation, prevLocation, replyTo) =>
          if (pbState.ownerId.contains(ownerId))
            Effect
              .persist(
                Released(ownerId, newLocation /*, prevLocation*/ )
              )
              .thenReply(resolver.resolveActorRef(replyTo)) { _ =>
                ctx.log.warn(s"Released($ownerId)")
                StatusReply.success(Done)
              }
          else
            Effect
              .none[Event, TakenDefinitionState]
              .thenReply(resolver.resolveActorRef(replyTo)) { _: TakenDefinitionState =>
                ctx.log.warn(s"Failed to release prev_payload for $ownerId: Not found")
                StatusReply.success(Done)
              }

        case Passivate() =>
          Effect
            .none[Event, TakenDefinitionState]
            .thenRun(_ => ctx.log.info(s"Passivated: ${pbState.ownerId}"))
            .thenStop()
            .thenNoReply()
      }

    def applyEvt(
      event: Event
    )(implicit ctx: ActorContext[Cmd]): TakenDefinitionState =
      event match {
        case Acquired(ownerId, location, _) =>
          ctx.log.info("Acquired:{} by ownerId={}", location, ownerId)
          pbState.update(_.optionalOwnerId := Some(ownerId))
        case Released(ownerId, newLocation /*, prevLocation*/ ) =>
          ctx.log.info("Release {} ownerId={}", newLocation, ownerId)
          pbState.update(_.optionalOwnerId := None)

      }
  }
}
