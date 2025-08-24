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
    Tables.lockFreeCreate(in) { in =>
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
    Tables.lockFreeUpdate(in) { (in, prevDefinitionLocation) =>
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
    }

  /*def update(in: PutRequest) =
    Tables.withUpdateLock(in) { (in, prevDefinitionLocation) =>
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
