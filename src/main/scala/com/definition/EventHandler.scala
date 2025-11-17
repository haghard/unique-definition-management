/*
package com.definition

import com.definition.domain.command.Cmd
import org.apache.pekko
import org.apache.pekko.Done
import pekko.projection.r2dbc.scaladsl.R2dbcHandler
import pekko.projection.r2dbc.scaladsl.R2dbcSession
import pekko.persistence.query.typed.EventEnvelope
import com.definition.domain.event.*
import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.projection.ProjectionId

import java.util.UUID
import scala.concurrent.*

class EventHandler(projectionId: ProjectionId, takenDefinitions: ActorRef[Cmd])(implicit
  system: ActorSystem[_],
  resolver: ActorRefResolver
) extends R2dbcHandler[EventEnvelope[Event]]
    with Measure {
  import system.executionContext

  private var totalCount          = 0
  private var throughputStartTime = System.nanoTime()
  private var throughputCount     = 0

  override val logId = projectionId.id

  implicit val askTo: org.apache.pekko.util.Timeout        = Guardian.askTo
  implicit val sch: org.apache.pekko.actor.typed.Scheduler = system.scheduler

  override def process(session: R2dbcSession, envelope: EventEnvelope[Event]): Future[Done] = {
    val pid = PersistenceId.extractEntityId(envelope.persistenceId)
    println("<----? " + pid)

    envelope.event match {
      case a: Acquired =>
        a.prevDefinitionLocation match {
          case Some(prevDefinitionLocation) =>
            takenDefinitions.askWithStatus[Done](replyTo =>
              com.definition.domain.command.Replace(
                ownerId = a.ownerId,
                definition = a.definition,
                acquiredSeqNum = a.seqNum,
                acquiredBucketId = pid.toLong,
                prevDefinitionLocation = prevDefinitionLocation,
                replyTo = resolver.toSerializationFormat(replyTo)
              )
            )

          case None =>
            val stmt = session
              .createStatement(
                "INSERT into definition_index_view (name, definition, ownerId, bucketId, sequenceNr, is_locked, ts) VALUES ($1, $2, $3, $4, $5, $6, $7)"
              )
              .bind(0, a.definition.name)
              .bind(1, a.definition.toByteArray)
              .bind(2, a.ownerId)
              .bind(3, pid.toLong)
              .bind(4, a.seqNum)
              .bind(5, false)
              .bind(6, envelope.timestamp)
            session
              .updateOne(stmt)
              .map(_ => Done)
              .recover { ex =>
                ex.printStackTrace()
                Done
              }

            val row =
              DefinitionIndexViewRow(
                name = a.definition.name,
                definition = a.definition,
                ownerId = UUID.fromString(a.ownerId),
                bucketId = envelope.persistenceId.toLong,
                sequenceNr = a.seqNum,
                when = envelope.timestamp
              )
            RelationalData.definitionIndexView.createAndUnlock(row)

        }

      case r: Released =>
        // Future.successful(Done)

        val row =
          DefinitionIndexViewRow(
            name = r.definition.name,
            definition = r.definition,
            ownerId = UUID.fromString(r.ownerId),
            bucketId = r.acquiredBucketId,
            sequenceNr = r.acquiredSeqNum,
            when = envelope.timestamp
          )
        RelationalData.definitionIndexView.updateAndUnlock(row)
    }

    /*envelope.event match {
      case ShoppingCart.CheckedOut(cartId, time) =>
        //logger.info(s"Shopping cart $cartId was checked out at $time")
        val stmt = session
          .createStatement("INSERT into order (id, time) VALUES ($1, $2)")
          .bind(0, cartId)
          .bind(1, time)
        session
          .updateOne(stmt)
          .map(_ => Done)

      case otherEvent =>
        //logger.debug(s"Shopping cart ${otherEvent.cartId} changed by $otherEvent")
        Future.successful(Done)
    }*/
  }
}
 */
