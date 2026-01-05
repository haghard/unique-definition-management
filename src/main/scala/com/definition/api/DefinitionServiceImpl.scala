package com.definition.api

import akka.actor.typed.*
import akka.actor.typed.scaladsl.AskPattern.Askable
import scala.concurrent.*
import com.definition.domain.*
import com.definition.*
import com.definition.domain.command.*

final class DefinitionServiceImpl(
  takenDefinitions: ActorRef[Cmd],
  definitionTables: Vector[String]
)(implicit system: ActorSystem[_], timeout: akka.util.Timeout)
    extends DefinitionService {

  val actorRefResolver: ActorRefResolver = ActorRefResolver(system)

  implicit val sch: Scheduler       = system.scheduler
  implicit val ec: ExecutionContext = system.executionContext

  val r2dbcDao = new R2dbcDao(system)

  override def conditionalPut(in: PutRequest): Future[PutReply] =
    in.location match {
      case None =>
        create(in)
      case Some(_) =>
        update(in)
    }

  override def getDefinitionLocation(in: GetDefinitionLocationRequest): Future[GetDefinitionLocationReply] =
    r2dbcDao.getDefinition(in.ownerId, tables.definitionTableByOwner(definitionTables, in.ownerId))

  private def askCreate(in: PutRequest) =
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
        )
      }

  private def askUpdate(in: PutRequest, currentLocation: DefinitionLocation) =
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

  def create(in: PutRequest) = {

    val dbResult = r2dbcDao.create(in, tables.definitionTableByOwner(definitionTables, in.ownerId))
    dbResult.flatMap {
      case RequestResult.Ok(location) =>
        Future.successful(
          PutReply(
            in.ownerId,
            PutReply.StatusCode.OKNoOp,
            location
          )
        )

      case RequestResult.Placed =>
        askCreate(in)

      case RequestResult.Resend(request) =>
        askCreate(request)

      case RequestResult.ResendInFlightRequestOnConflict(request) =>
        askCreate(request).flatMap(_ =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.ConcurrentModification,
              DefinitionLocation(-1, -1)
            )
          )
        )

      case RequestResult.ConcurrentModification =>
        Future.successful(
          PutReply(
            in.ownerId,
            PutReply.StatusCode.ConcurrentModification,
            DefinitionLocation(-1, -1)
          )
        )

      case RequestResult.OwnerReserved(entityId, seqNum) =>
        Future.successful(
          PutReply(
            in.ownerId,
            PutReply.StatusCode.OwnerReserved,
            DefinitionLocation(entityId, seqNum)
          )
        )

      case RequestResult.LocationNotFound | RequestResult.NotFound | RequestResult.Update =>
        Future.successful(
          PutReply(
            in.ownerId,
            PutReply.StatusCode.Unrecognized(-1),
            DefinitionLocation(-1, -1)
          )
        )
    }
  }

  def update(in: PutRequest) = {
    val dbResult = r2dbcDao.update(in, tables.definitionTableByOwner(definitionTables, in.ownerId))
    dbResult
      .flatMap { r =>
        r match {
          case RequestResult.Ok(location) =>
            Future.successful(
              PutReply(
                in.ownerId,
                PutReply.StatusCode.OKNoOp,
                location
              )
            )

          case RequestResult.Placed =>
            askUpdate(in, in.getLocation)

          case RequestResult.Resend(reqInFlight) =>
            askUpdate(reqInFlight, reqInFlight.getLocation)

          case RequestResult.ResendInFlightRequestOnConflict(reqInFlight) =>
            askUpdate(reqInFlight, reqInFlight.getLocation).flatMap(_ =>
              Future.successful(
                PutReply(
                  in.ownerId,
                  PutReply.StatusCode.ConcurrentModification,
                  DefinitionLocation(-1, -1)
                )
              )
            )

          case RequestResult.ConcurrentModification =>
            Future.successful(
              PutReply(
                in.ownerId,
                PutReply.StatusCode.ConcurrentModification,
                DefinitionLocation(-1, -1)
              )
            )

          case RequestResult.NotFound =>
            Future.successful(
              PutReply(
                in.ownerId,
                PutReply.StatusCode.OwnerNotFound,
                DefinitionLocation(-1, -1)
              )
            )

          case RequestResult.LocationNotFound =>
            Future.successful(
              PutReply(
                in.ownerId,
                PutReply.StatusCode.LocationNotFound,
                DefinitionLocation(-1, -1)
              )
            )

          case _: RequestResult.OwnerReserved | RequestResult.Update =>
            Future.successful(
              PutReply(
                in.ownerId,
                PutReply.StatusCode.Unrecognized(-1),
                DefinitionLocation(-1, -1)
              )
            )
        }
      }
  }
}
