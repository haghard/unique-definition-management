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

  implicit val sch: Scheduler                = system.scheduler
  implicit val askTimeout: akka.util.Timeout = akka.util.Timeout(3.seconds)

  val actorRefResolver: ActorRefResolver = ActorRefResolver(system)

  override def conditionalPut(in: PutRequest): Future[DefinitionReply] =
    in.seqNum match {
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

      case n if n > 0 =>
        Tables.ownership
          .getLocationByOwnerId(UUID.fromString(in.ownerId))
          .flatMap { rows =>
            rows.size match {
              case 0 =>
                Future.successful(
                  DefinitionReply(
                    in.ownerId,
                    com.definition.api.DefinitionReply.StatusCode.NotFound,
                    DefinitionLocation(-1, -1)
                  )
                )
              case 1 =>
                val (entityId, seqNum, definition) = rows.head

                if (in.definition != definition) {
                  if (seqNum == in.seqNum) {
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
                  } else {
                    Future.successful(
                      DefinitionReply(
                        in.ownerId,
                        com.definition.api.DefinitionReply.StatusCode.NotFound,
                        DefinitionLocation(entityId, seqNum)
                      )
                    )
                  }
                } else {
                  Future.successful(
                    DefinitionReply(
                      in.ownerId,
                      com.definition.api.DefinitionReply.StatusCode.OK2,
                      DefinitionLocation(entityId, seqNum)
                    )
                  )
                }

              case n =>
                Future.successful(
                  DefinitionReply(
                    in.ownerId,
                    com.definition.api.DefinitionReply.StatusCode.IllegalState,
                    DefinitionLocation(-1, -1)
                  )
                )
            }
          }(system.executionContext)

      case n =>
        Future.successful(
          DefinitionReply(
            in.ownerId,
            com.definition.api.DefinitionReply.StatusCode.IllegalState,
            DefinitionLocation(-1, n)
          )
        )
    }
}
