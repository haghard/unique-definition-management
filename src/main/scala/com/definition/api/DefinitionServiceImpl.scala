package com.definition.api

import akka.actor.typed.*
import akka.actor.typed.scaladsl.AskPattern.Askable

import scala.concurrent.*
import com.definition.domain.*
import com.definition.*
import com.definition.domain.command.*

import java.util.UUID

final class DefinitionServiceImpl(
  takenDefinitions: ActorRef[Cmd]
)(implicit system: ActorSystem[_], timeout: akka.util.Timeout)
    extends DefinitionService {

  val actorRefResolver: ActorRefResolver = ActorRefResolver(system)

  implicit val sch: Scheduler       = system.scheduler
  implicit val ec: ExecutionContext = system.executionContext

  override def conditionalPut(in: PutRequest): Future[PutReply] =
    in.location match {
      case None =>
        create(in)
      case Some(_) =>
        update(in)
    }

  override def getDefinitionLocation(in: GetDefinitionLocationRequest): Future[GetDefinitionLocationReply] =
    Tables
      .definitionTableByOwner(in.ownerId)
      .getLocationDefinition(UUID.fromString(in.ownerId))
      .map {
        case None      => GetDefinitionLocationReply(None, None)
        case Some(row) =>
          val (hashBucketId, seqNum, definition) = row
          GetDefinitionLocationReply(Some(DefinitionLocation(hashBucketId, seqNum)), Some(definition))
        case _ =>
          GetDefinitionLocationReply(Some(DefinitionLocation(-1, -1)), None)
      }

  def create(in: PutRequest) =
    Tables.create(in) { in =>
      takenDefinitions
        .askWithStatus[PutReply] { replyTo =>
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
            actorRefResolver.toSerializationFormat(replyTo)
          ) // .withReplyTo(replyTo)
        }
    }

  def update(in: PutRequest) =
    Tables.update(in) { (in, currentLocation) =>
      takenDefinitions
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
            currentLocation,
            actorRefResolver.toSerializationFormat(replyTo)
          )
        }
    }
}
