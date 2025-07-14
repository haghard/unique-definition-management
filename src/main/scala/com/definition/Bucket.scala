/*
package com.definition

import akka.Done
import akka.actor.typed.*
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.pattern.StatusReply
import akka.persistence.typed.*
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect}
import com.definition.Implicits.Ops
import com.definition.api.DefinitionReply
import com.definition.domain.{Acquired, Cmd as PbCmd, Create, Event as PbEvent, Passivate, Release, ReleaseRequested, Released, TakenDefinitionBucketState, Update}

import scala.concurrent.duration.DurationInt

object Bucket {

  def apply(entityId: String): Behavior[PbCmd] =
    Behaviors.setup { implicit ctx =>
      Behaviors.withTimers { timer =>
        timer.startSingleTimer(entityId, Passivate(), 15.seconds)

        implicit val resolver: ActorRefResolver = ActorRefResolver(ctx.system)
        val path                                = ctx.self.path

        EventSourcedBehavior
          .withEnforcedReplies[PbCmd, PbEvent, TakenDefinitionBucketState](
            PersistenceId.ofUniqueId(entityId),
            TakenDefinitionBucketState(),
            (state, cmd) => state.applyCmd(cmd, path.toStringWithoutAddress),
            (state, event) => state.applyEvt(event)
          )
          .withTagger(_ => Set(math.abs(entityId.toInt % Guardian.numberOfTags).toString))
          .receiveSignal {
            case (state, RecoveryCompleted) =>
              ctx.log.warn(s"RecoveryCompleted $path - ${state.index.values.take(3)}")
            case (state, SnapshotFailed(_, ex)) =>
              ctx.log.error(s"Saving snapshot $state failed", ex)
            case (_, RecoveryFailed(cause)) =>
              ctx.log.error(s"There is a problem with state recovery $cause", cause)
          }
          .onPersistFailure(
            SupervisorStrategy.restartWithBackoff(minBackoff = 3.seconds, maxBackoff = 10.seconds, randomFactor = 0.3)
          )
      }
    }

  implicit class TakenDefinitionBucketStateOps(val self: TakenDefinitionBucketState) extends AnyVal {
    def applyCmd(
      cmd: PbCmd,
      path: String
    )(implicit
      ctx: ActorContext[PbCmd],
      resolver: ActorRefResolver
    ): ReplyEffect[PbEvent, TakenDefinitionBucketState] = {
      val log = ctx.log
      cmd match {
        case Create(ownerId, definition, replyTo) =>
          log.info(s"$path---> Create ${definition.name} to $ownerId")
          self.index.get(definition.contentKey) match {
            case Some(currentOwnerId) =>
              Effect
                .none[PbEvent, TakenDefinitionBucketState]
                .thenReply(resolver.resolveActorRef(replyTo)) { _: TakenDefinitionBucketState =>
                  if (currentOwnerId == ownerId) {
                    log.warn("Ignore duplicate Create")
                    // val slotNum = EventSourcedBehavior.lastSequenceNumber(ctx) + 1
                    StatusReply.success(DefinitionReply(ownerId, DefinitionReply.StatusCode.OK))
                  } else {
                    log.warn(s"Reserved by $currentOwnerId")
                    StatusReply.success(
                      DefinitionReply(ownerId, DefinitionReply.StatusCode.Reserved)
                    )
                  }
                }
            case None =>
              Effect
                .persist(Acquired(ownerId, definition))
                .thenReply(resolver.resolveActorRef(replyTo)) { state =>
                  log.info(
                    s"$path Acquired ${definition.name} to $ownerId  Owners:${self.index.take(5).mkString(",")}"
                  )
                  StatusReply.success(
                    DefinitionReply(ownerId, DefinitionReply.StatusCode.OK)
                  )
                }
          }

        case Update(ownerId, definition, prevDefinition, replyTo) =>
          log.info(s"$path  ---> Update from ${prevDefinition.name} to ${definition.name}  $ownerId")

          self.index.get(definition.contentKey) match {
            case Some(currentOwnerId) =>
              Effect
                .none[PbEvent, TakenDefinitionBucketState]
                .thenReply(resolver.resolveActorRef(replyTo)) { _: TakenDefinitionBucketState =>
                  if (currentOwnerId == ownerId) {
                    log.warn("IGNORE: duplicate Update")
                    StatusReply.success(
                      DefinitionReply(ownerId, DefinitionReply.StatusCode.OK)
                    )
                  } else {
                    log.warn(s"Reserved by $currentOwnerId")
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

        case Release(ownerId, definition, replyTo) =>
          log.info(s"$path ---> Release old_definition for $ownerId")
          self.index.get(definition.contentKey) match {
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
            .thenRun(_ => log.info(s"Passivated: [Size:${self.index.size}]"))
            .thenStop()
            .thenNoReply()
      }
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
 */
