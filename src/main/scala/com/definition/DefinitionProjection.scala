package com.definition

import akka.Done
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorRef, ActorRefResolver, ActorSystem}
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.persistence.Persistence
import akka.persistence.query.Offset
import akka.persistence.query.typed.EventEnvelope
import akka.persistence.r2dbc.internal.Sql.Interpolation
import akka.projection.r2dbc.scaladsl.*

import scala.concurrent.*
import akka.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import akka.persistence.typed.PersistenceId
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.scaladsl.SourceProvider
import akka.projection.{ProjectionBehavior, ProjectionId}
import com.definition.domain.DefinitionLocation
import com.definition.domain.command.Cmd
import com.definition.domain.event.*

object DefinitionProjection {

  def mkProjection(
    system: ActorSystem[_],
    sliceRange: Range,
    takenDefinitions: ActorRef[Cmd],
    projectionName: String,
    definitionTables: Vector[String]
  )(implicit timeout: akka.util.Timeout) = {
    val minSlice                   = sliceRange.min
    val maxSlice                   = sliceRange.max
    val projectionId               = ProjectionId(projectionName, s"events-$minSlice-$maxSlice")
    val resolver: ActorRefResolver = ActorRefResolver(system)

    implicit val ec: ExecutionContext = ExecutionContext.parasitic

    val entityType: String                                           = TakenDefinition.TypeKey.name
    val sourceProvider: SourceProvider[Offset, EventEnvelope[Event]] =
      EventSourcedProvider.eventsBySlices[Event](system, R2dbcReadJournal.Identifier, entityType, minSlice, maxSlice)

    val persistence = Persistence(system)

    /*
    session.updateOne(session
      .createStatement(
        "INSERT INTO item_popularity (itemid, count) VALUES ($1, $2) " +
        "ON CONFLICT(itemid) DO UPDATE SET count = item_popularity.count + $3")
      .bind(0, itemId)
      .bind(1, delta)
      .bind(2, delta))

      session.selectOne(
      session
        .createStatement("SELECT count FROM item_popularity WHERE itemid = $1")
        .bind(0, itemId)) { row =>
      row.get("count", classOf[java.lang.Long])
    }
     */

    R2dbcProjection.exactlyOnce(
      projectionId,
      settings = None,
      sourceProvider,
      handler = () =>
        (session: R2dbcSession, env: EventEnvelope[Event]) =>
          // Thread.sleep(10_000)
          env.event match {
            case acquired: Acquired =>
              val slice = persistence.sliceForPersistenceId(PersistenceId.extractEntityId(env.persistenceId))

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
                  val tableName = tables.definitionTableByOwner(definitionTables, acquired.ownerId)
                  session
                    .update(
                      Vector(
                        session
                          .createStatement(
                            sql"INSERT INTO $tableName (name,definition,owner_id,hash_bucket_id,seq_num,time) VALUES (?,?,CAST(? AS UUID),?,?,?)"
                          )
                          .bind(0, acquired.definition.name)
                          .bind(1, acquired.definition.toByteArray)
                          .bind(2, acquired.ownerId)
                          .bind(3, PersistenceId.extractEntityId(env.persistenceId).toLong)
                          .bind(4, acquired.seqNum)
                          .bind(5, env.timestamp),
                        session
                          .createStatement(sql"DELETE FROM pending_requests WHERE owner_id = CAST(? AS UUID)")
                          .bind(0, acquired.ownerId)
                      )
                    )
                    .map { rs => println(rs.mkString(",")); Done }
              }

            case conflict: ConflictDetected =>
              session
                .updateOne(
                  session
                    .createStatement(sql"DELETE FROM pending_requests WHERE owner_id = CAST(? AS UUID) AND tag = ?")
                    .bind(0, conflict.ownerId)
                    .bind(1, conflict.conflictTag.value)
                )
                .map(_ => Done)(ExecutionContext.parasitic)

            case released: Released =>
              val tableName = tables.definitionTableByOwner(definitionTables, released.ownerId)
              session
                .update(
                  Vector(
                    session
                      .createStatement(
                        sql"UPDATE $tableName SET hash_bucket_id = ?, seq_num = ?, definition = ?, name = ?, time = ? WHERE owner_id = CAST(? AS UUID)"
                      )
                      .bind(0, released.acquiredLocation.bucketId)
                      .bind(1, released.acquiredLocation.seqNum)
                      .bind(2, released.acquiredDefinition.toByteArray)
                      .bind(3, released.acquiredDefinition.name)
                      .bind(4, env.timestamp)
                      .bind(5, released.ownerId),
                    session
                      .createStatement(sql"DELETE FROM pending_requests WHERE owner_id = CAST(? AS UUID)")
                      .bind(0, released.ownerId)
                  )
                )
                .map { rs => println("Released:" + rs.mkString(",")); Done }
          }
    )(system)
  }

  def run(
    takenDefinitions: ActorRef[Cmd],
    numberOfTags: Int,
    definitionTables: Vector[String]
  )(implicit system: ActorSystem[_], timeout: akka.util.Timeout): Unit = {
    val projectionName           = "dfn-proj"
    val numberOfSliceRanges: Int = numberOfTags
    val sliceRanges = EventSourcedProvider.sliceRanges(system, R2dbcReadJournal.Identifier, numberOfSliceRanges)

    ShardedDaemonProcess(system)
      .init(
        projectionName,
        numberOfTags,
        index =>
          ProjectionBehavior(
            mkProjection(system, sliceRanges(index), takenDefinitions, projectionName, definitionTables)
          ),
        ShardedDaemonProcessSettings(system),
        Some(ProjectionBehavior.Stop)
      )
  }

}
