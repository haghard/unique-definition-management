package com.definition.api

import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable

import scala.concurrent.*
import com.definition.*
import com.definition.domain.DefinitionLocation
import com.definition.domain.command.*

import java.util.UUID

final class DefinitionServiceImpl(
  takenDefinitions: ActorRef[Cmd]
)(implicit system: ActorSystem[_])
    extends DefinitionService {

  implicit val sch: Scheduler                            = system.scheduler
  implicit val askTimeout: org.apache.pekko.util.Timeout = Guardian.askTo
  implicit val ec: ExecutionContext                      = system.executionContext

  val lockTTL: Long                      = Guardian.askTo.duration.toMillis * 3
  val actorRefResolver: ActorRefResolver = ActorRefResolver(system)
  val numberOfShards                     = system.settings.config.getInt("pekko.cluster.sharding.number-of-shards")

  override def conditionalPut(in: PutRequest): Future[PutReply] =
    in.location match {
      case None =>
        create(in)
      case Some(_) =>
        update(in)
    }

  override def getCurrentValue(in: GetDefinitionLocationRequest): Future[GetDefinitionLocationReply] =
    RelationalData.definitionIndexView
      .getCurrentLocation(UUID.fromString(in.ownerId))
      .map {
        case None =>
          GetDefinitionLocationReply(None)
        case Some((shardId, definitionId)) =>
          GetDefinitionLocationReply(Some(DefinitionLocation(shardId, definitionId)))
      }

  def create(in: PutRequest) =
    RelationalData.create(in, lockTTL, math.abs(in.definition.hashCode() % numberOfShards)) { (in, definitionLocation) =>
      takenDefinitions
        .askWithStatus[PutReply] { askReplyTo =>
          Create(in.ownerId, definitionLocation, actorRefResolver.toSerializationFormat(askReplyTo))
        }
    }(ec)

  def update(in: PutRequest) =
    RelationalData.update(in, lockTTL, math.abs(in.definition.hashCode() % numberOfShards)) {
      (in, prevLocation, newLocation) =>
        takenDefinitions
          .askWithStatus[PutReply] { replyTo =>
            Update(
              in.ownerId,
              newLocation,
              prevLocation,
              actorRefResolver.toSerializationFormat(replyTo)
            )
          }
    }(ec)
}
