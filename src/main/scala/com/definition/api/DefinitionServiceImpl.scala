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
)(implicit system: ActorSystem[_])
    extends DefinitionService {

  val actorRefResolver: ActorRefResolver = ActorRefResolver(system)

  implicit val sch: Scheduler                = system.scheduler
  implicit val askTimeout: akka.util.Timeout = Guardian.askTo
  implicit val ec: ExecutionContext          = system.executionContext
  val lockTTL: Long                          = Guardian.askTo.duration.toMillis * 3

  override def conditionalPut(in: PutRequest): Future[PutReply] =
    in.location match {
      case None =>
        create(in)
      case Some(_) =>
        update(in)
    }

  override def getDefinitionLocation(in: GetDefinitionLocationRequest): Future[GetDefinitionLocationReply] =
    Tables.definitionIndexView
      .getLocationDefinition(UUID.fromString(in.ownerId))
      .map {
        case None      => GetDefinitionLocationReply(None, None)
        case Some(row) =>
          val (bucketId, seqNum, definition) = row
          GetDefinitionLocationReply(Some(DefinitionLocation(bucketId, seqNum)), Some(definition))
        case _ =>
          GetDefinitionLocationReply(Some(DefinitionLocation(-1, -1)), None)
      }

  def create(in: PutRequest) =
    Tables.create(in, lockTTL) { in =>
      takenDefinitions
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
    }(ec)

  def update(in: PutRequest) =
    Tables.update(in, lockTTL) { (in, currentLocation) =>
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

  /*def update(in: PutRequest) =
    Tables.lockFreeStatusUpdate(in) { (in, prevDefinitionLocation) =>
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
            prevDefinitionLocation,
            actorRefResolver.toSerializationFormat(replyTo)
          )
        }
    }*/
}
