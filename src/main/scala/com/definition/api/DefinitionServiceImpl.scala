package com.definition.api

import akka.actor.typed.*
import akka.actor.typed.scaladsl.AskPattern.Askable

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import com.definition.domain.*
import com.definition.Tables
import com.definition.domain.Cmd as PbCmd

import java.util.UUID

final class DefinitionServiceImpl(
  shardRegion: ActorRef[PbCmd]
)(implicit system: ActorSystem[_])
    extends DefinitionService {

  val actorRefResolver: ActorRefResolver = ActorRefResolver(system)

  implicit val sch: Scheduler                                = system.scheduler
  implicit val askTimeout: akka.util.Timeout                 = akka.util.Timeout(7.seconds)
  implicit val ec: scala.concurrent.ExecutionContextExecutor = system.executionContext

  override def conditionalPut(in: PutRequest): Future[PutReply] =
    in.definitionLocation match {
      case None =>
        create(in)
      case Some(_) =>
        update(in)
    }

  override def getDefinitionLocation(in: GetDefinitionLocationRequest): Future[GetDefinitionLocationReply] =
    Tables.definitionIndexView
      .getLocationDefinition(UUID.fromString(in.ownerId))
      .map { rows =>
        rows.size match {
          case 0 => GetDefinitionLocationReply(None, None)
          case 1 =>
            val (entityId, seqNum, definition) = rows.head
            GetDefinitionLocationReply(Some(DefinitionLocation(entityId, seqNum)), Some(definition))
          case _ =>
            GetDefinitionLocationReply(Some(DefinitionLocation(-1, -1)), None)
        }
      }

  def create(in: PutRequest) =
    Tables.definitionIndexView
      .getLocationDefinition(UUID.fromString(in.ownerId))
      .flatMap { rows =>
        rows.size match {
          case 0 =>
            // Concurrent modifications resolutions strategy = (detect it on the read side and rollback)
            shardRegion
              .askWithStatus[PutReply] { askReplyTo =>
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
            val (entityId, seqNum, definition) = rows.head
            if (definition == in.definition) {
              Future.successful(
                PutReply(
                  in.ownerId,
                  PutReply.StatusCode.OK2,
                  Some(DefinitionLocation(entityId, seqNum))
                )
              )
            } else {
              Future.successful(
                PutReply(
                  in.ownerId,
                  PutReply.StatusCode.AnotherDefinitionFound,
                  Some(DefinitionLocation(entityId, seqNum))
                )
              )
            }
          case _ =>
            Future.successful(
              PutReply(
                in.ownerId,
                PutReply.StatusCode.IllegalState,
                Some(DefinitionLocation(-1, -1))
              )
            )
        }
      }

  def update(in: PutRequest) =
    Tables.definitionIndexView
      .getLocationDefinition(UUID.fromString(in.ownerId))
      .flatMap { rows =>
        rows.size match {
          case 0 =>
            Future.successful(
              PutReply(
                in.ownerId,
                PutReply.StatusCode.NotFound,
                Some(DefinitionLocation(-1, -1))
              )
            )
          case 1 =>
            // Concurrent modifications resolutions strategy = (detect it on the read side and rollback)
            val (bucketId, seqNum, definition) = rows.head
            if (in.definition != definition) {
              if (bucketId == in.getDefinitionLocation.bucketId && seqNum == in.getDefinitionLocation.seqNum) {
                shardRegion
                  .askWithStatus[PutReply] { replyTo =>
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
                      DefinitionLocation(bucketId, seqNum),
                      actorRefResolver.toSerializationFormat(replyTo)
                    )
                  }
              } else {
                Future.successful(
                  PutReply(
                    in.ownerId,
                    PutReply.StatusCode.LocationNotFound,
                    Some(DefinitionLocation(-1, -1))
                  )
                )
              }
            } else {
              Future.successful(
                PutReply(
                  in.ownerId,
                  PutReply.StatusCode.OK2,
                  Some(DefinitionLocation(bucketId, seqNum))
                )
              )
            }

          case n =>
            // fix on read ???
            Future.successful(
              PutReply(
                in.ownerId,
                PutReply.StatusCode.IllegalState,
                Some(DefinitionLocation(-1, -1))
              )
            )
        }
      }
}
