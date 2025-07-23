package com.definition.api

import akka.actor.typed.*
import akka.actor.typed.scaladsl.AskPattern.Askable

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import com.definition.domain.*
import com.definition.Tables
import com.definition.domain.Cmd as PbCmd

final class DefinitionServiceImpl(
  shardRegion: ActorRef[PbCmd]
)(implicit system: ActorSystem[_])
    extends DefinitionService {

  implicit val sch: Scheduler                = system.scheduler
  implicit val askTimeout: akka.util.Timeout = akka.util.Timeout(3.seconds)

  val actorRefResolver: ActorRefResolver = ActorRefResolver(system)

  override def create(in: CreateRequest): Future[DefinitionReply] =
    Tables.ownership
      .definitionByOwnerId(in.ownerId)
      .flatMap { rows =>
        rows.size match {
          case 0 =>
            shardRegion
              .askWithStatus[DefinitionReply] { askReplyTo =>
                Create(
                  in.ownerId,
                  Definition(
                    in.definition.name,
                    in.definition.address,
                    in.definition.city,
                    in.definition.country,
                    in.definition.state,
                    in.definition.zipCode,
                    in.definition.brand
                  ),
                  actorRefResolver.toSerializationFormat(askReplyTo)
                )
              }
          case 1 =>
            val (entityId, seqNum, existingDefinition) = rows(0)
            if (existingDefinition == in.definition) {
              Future.successful(
                DefinitionReply(
                  in.ownerId,
                  DefinitionReply.StatusCode.OK,
                  DefinitionLocation(entityId, seqNum)
                )
              )
            } else {
              Future.successful(
                DefinitionReply(
                  in.ownerId,
                  com.definition.api.DefinitionReply.StatusCode.IllegalCreate,
                  DefinitionLocation()
                )
              )
            }
          case _ =>
            Future.successful(
              DefinitionReply(
                in.ownerId,
                com.definition.api.DefinitionReply.StatusCode.IllegalCreate,
                DefinitionLocation()
              )
            )
        }
      }(system.executionContext)

  override def update(in: UpdateDefinitionRequest): Future[DefinitionReply] =
    Tables.ownership
      .definitionByOwnerId(in.ownerId)
      .flatMap { rows =>
        rows.size match {
          case 1 =>
            val (entityId, seqNum, _) = rows(0)
            shardRegion
              .askWithStatus[DefinitionReply] { replyTo =>
                Update(
                  in.ownerId,
                  Definition(
                    in.definition.name,
                    in.definition.address,
                    in.definition.city,
                    in.definition.country,
                    in.definition.state,
                    in.definition.zipCode,
                    in.definition.brand
                  ),
                  DefinitionLocation(entityId, seqNum),
                  actorRefResolver.toSerializationFormat(replyTo)
                )
              }
          case 0 =>
            Future.successful(
              DefinitionReply(
                in.ownerId,
                com.definition.api.DefinitionReply.StatusCode.NotFound,
                DefinitionLocation()
              )
            )
          case _ =>
            // When we process a `UpdateDefinitionRequest`, we send out our DefinitionReply back without waiting for all state changes. ("Realise" happens asynchronously)
            // If you see more than 1 row here, it indicates that the projection layer hasn't applied N (where N > 1) previous updates yet by this owner_id.
            // We want to detect.
            Future.successful(
              DefinitionReply(
                in.ownerId,
                com.definition.api.DefinitionReply.StatusCode.IllegalState,
                DefinitionLocation()
              )
            )
        }
      }(system.executionContext)
}
