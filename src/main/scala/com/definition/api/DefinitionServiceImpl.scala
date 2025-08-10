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
  implicit val askTimeout: akka.util.Timeout                 = akka.util.Timeout(3.seconds)
  implicit val ec: scala.concurrent.ExecutionContextExecutor = system.executionContext

  override def conditionalPut(in: PutRequest): Future[PutReply] =
    in.causalToken match {
      case 0 =>
        create(in)

      case causalToken =>
        if (causalToken > 0)
          update(in)
        else
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.InvalidCausalToken,
              -1
            )
          )
    }

  override def getCausalToken(in: GetCausalTokenRequest): Future[GetCausalTokenReply] =
    Tables.definitionIndexView
      .getCausalToken(UUID.fromString(in.ownerId))
      .map(tokenOpt => GetCausalTokenReply(tokenOpt.getOrElse(0)))

  def create(in: PutRequest) =
    Tables.definitionIndexView
      .getLocationByOwnerId(UUID.fromString(in.ownerId))
      .flatMap { rows =>
        rows.size match {
          case 0 =>
            // Unprotected concurrent access is allowed
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
            val (_, _, definition, causalToken) = rows.head
            if (definition == in.definition) {
              Future.successful(
                PutReply(
                  in.ownerId,
                  PutReply.StatusCode.OK2,
                  causalToken
                )
              )
            } else {
              Future.successful(
                PutReply(
                  in.ownerId,
                  PutReply.StatusCode.AnotherDefinitionFound,
                  causalToken
                )
              )
            }
          case _ =>
            Future.successful(
              PutReply(
                in.ownerId,
                PutReply.StatusCode.IllegalState,
                -1
              )
            )
        }
      }

  def update(in: PutRequest) =
    Tables.definitionIndexView
      .getLocationByOwnerId(UUID.fromString(in.ownerId))
      .flatMap { rows =>
        rows.size match {
          case 0 =>
            Future.successful(
              PutReply(
                in.ownerId,
                PutReply.StatusCode.NotFound,
                -1
              )
            )
          case 1 =>
            // Unprotected concurrent access is allowed
            val (entityId, seqNum, definition, causalToken) = rows.head
            if (in.definition != definition) {
              if (causalToken == in.causalToken) {
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
                      DefinitionLocation(entityId, seqNum),
                      actorRefResolver.toSerializationFormat(replyTo)
                    )
                  }
              } else {
                Future.successful(
                  PutReply(
                    in.ownerId,
                    PutReply.StatusCode.CausalTokenNotFound,
                    causalToken
                  )
                )
              }
            } else {
              Future.successful(
                PutReply(
                  in.ownerId,
                  PutReply.StatusCode.OK2,
                  causalToken
                )
              )
            }

          case n =>
            // fix on read ???
            Future.successful(
              PutReply(
                in.ownerId,
                PutReply.StatusCode.IllegalState,
                -1
              )
            )
        }
      }
}
