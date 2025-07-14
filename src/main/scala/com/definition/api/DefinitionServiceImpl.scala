package com.definition.api

import akka.actor.typed.*
import akka.actor.typed.scaladsl.AskPattern.Askable

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import com.definition.domain.*
import com.definition.domain.Cmd as PbCmd

final class DefinitionServiceImpl(
  shardRegion: ActorRef[PbCmd]
)(implicit system: ActorSystem[_])
    extends DefinitionService {

  implicit val sch: Scheduler                = system.scheduler
  implicit val askTimeout: akka.util.Timeout = akka.util.Timeout(5.seconds)

  val actorRefResolver: ActorRefResolver = ActorRefResolver(system)

  override def create(in: CreateRequest): Future[DefinitionReply] =
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

  override def update(in: UpdateDefinitionRequest): Future[DefinitionReply] =
    shardRegion
      .askWithStatus[DefinitionReply] { askReplyTo =>
        Update(
          in.ownerId,
          newDefinition = Definition(
            in.newDefinition.name,
            in.newDefinition.address,
            in.newDefinition.city,
            in.newDefinition.country,
            in.newDefinition.state,
            in.newDefinition.zipCode,
            in.newDefinition.brand
          ),
          prevDefinition = Definition(
            in.prevDefinition.name,
            in.prevDefinition.address,
            in.prevDefinition.city,
            in.prevDefinition.country,
            in.prevDefinition.state,
            in.prevDefinition.zipCode,
            in.prevDefinition.brand
          ),
          actorRefResolver.toSerializationFormat(askReplyTo)
        )
      }
}
