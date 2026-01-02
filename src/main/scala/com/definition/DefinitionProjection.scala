package com.definition

import akka.Done
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorRef, ActorRefResolver, ActorSystem}
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.typed.PersistenceId
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.scaladsl.SourceProvider
import akka.projection.slick.SlickProjection
import akka.projection.{ProjectionBehavior, ProjectionId}
import com.definition.domain.DefinitionLocation
import com.definition.domain.command.Cmd
import com.definition.domain.event.{Acquired, ConflictDetected, Event, Released}
import slick.basic.DatabaseConfig
import slick.jdbc.MySQLProfile

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

object DefinitionProjection {

  def mkProjection(
    tag: String,
    dbConfig: DatabaseConfig[MySQLProfile],
    name: String,
    takenDefinitions: ActorRef[Cmd]
  )(implicit system: ActorSystem[_], timeout: akka.util.Timeout) = {
    // PersistenceQuery(system).readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)
    val resolver: ActorRefResolver = ActorRefResolver(system)

    /*SlickProjection
      .groupedWithin(
        ProjectionId(name, tag),
        EventSourcedProvider.eventsByTag[PbEvent](system, JdbcReadJournal.Identifier, tag),
        dbConfig,
        () => ???
      )
      .withGroup(groupAfterEnvelopes = 10, groupAfterDuration = 300.millis)
     */

    /*
    val numberOfSlice = numberOfTags
    val sliceRanges   = EventSourcedProvider.sliceRanges(
      system,
      JdbcReadJournal.Identifier /*R2dbcReadJournal.Identifier*/,
      numberOfSlice
    )
    val minSlice           = sliceRanges.head.min
    val maxSlice           = sliceRanges.head.max
    val entityType: String = TakenDefinition.TypeKey.name
    val sp: SourceProvider[akka.persistence.query.Offset, akka.persistence.query.typed.EventEnvelope[Event]] =
      EventSourcedProvider.eventsBySlices[Event](system, JdbcReadJournal.Identifier, entityType, minSlice, maxSlice)*/

    val sourceProvider
      : SourceProvider[akka.persistence.query.Offset, akka.projection.eventsourced.EventEnvelope[Event]] =
      EventSourcedProvider.eventsByTag[Event](system, JdbcReadJournal.Identifier, tag)

    SlickProjection
      .atLeastOnceAsync(
        ProjectionId(name, tag),
        sourceProvider,
        dbConfig,
        () =>
          (env: akka.projection.eventsourced.EventEnvelope[Event]) => // akka.persistence.query.typed.EventEnvelope
            (env.event match {
              case acquired: Acquired =>
                acquired.releasedLocation match {
                  case Some(releasedLocation) =>
                    takenDefinitions.askWithStatus[Done](replyTo =>
                      com.definition.domain.command.Replace(
                        ownerId = acquired.ownerId,
                        definition = acquired.definition,
                        acquiredLocation =
                          DefinitionLocation(PersistenceId.extractEntityId(env.persistenceId).toLong, acquired.seqNum),
                        releasedLocation = releasedLocation,
                        replyTo = resolver.toSerializationFormat(replyTo)
                      )
                    )(timeout, system.scheduler)

                  case None =>
                    val row =
                      DefinitionRow(
                        name = acquired.definition.name,
                        definition = acquired.definition,
                        ownerId = UUID.fromString(acquired.ownerId),
                        bucketId = PersistenceId.extractEntityId(env.persistenceId).toLong,
                        sequenceNr = acquired.seqNum,
                        ts = env.timestamp
                      )

                    Tables.definitionTableByOwner(acquired.ownerId).create(row)
                }

              case conflict: ConflictDetected =>
                Tables
                  .definitionTableByOwner(conflict.ownerId)
                  .release(UUID.fromString(conflict.ownerId), conflict.conflictTag)

              case released: Released =>
                val row =
                  DefinitionRow(
                    name = released.acquiredDefinition.name,
                    definition = released.acquiredDefinition,
                    ownerId = UUID.fromString(released.ownerId),
                    bucketId = released.acquiredLocation.bucketId,
                    sequenceNr = released.acquiredLocation.seqNum,
                    ts = env.timestamp
                  )

                // Thread.sleep(15_000)
                Tables
                  .definitionTableByOwner(released.ownerId)
                  .update(row)

            }).map(_ => Done)(ExecutionContext.parasitic)
      )
      .withSaveOffset(afterEnvelopes = 10, afterDuration = 500.millis)
  }

  def run(
    region: ActorRef[Cmd],
    numberOfTags: Int
  )(implicit system: ActorSystem[_], timeout: akka.util.Timeout): Unit = {
    val dbConfig = DatabaseConfig.forConfig[MySQLProfile]("akka.projection.slick")
    val tags     = Vector.tabulate(numberOfTags)(_.toString)
    val name     = "events"

    ShardedDaemonProcess(system)
      .init(
        name,
        numberOfTags,
        tag => ProjectionBehavior(mkProjection(tags(tag), dbConfig, name, region)),
        ShardedDaemonProcessSettings(system),
        Some(ProjectionBehavior.Stop)
      )
  }

}
