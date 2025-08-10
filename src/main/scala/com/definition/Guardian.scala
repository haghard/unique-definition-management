package com.definition

import akka.Done
import akka.actor.RootActorPath
import akka.actor.typed.scaladsl.AskPattern.*
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import akka.actor.typed.{ActorRef, ActorRefResolver, ActorSystem, Behavior}
import akka.cluster.ddata.SelfUniqueAddress
import akka.cluster.sharding.typed.{ClusterShardingSettings, ShardedDaemonProcessSettings}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, ShardedDaemonProcess}
import akka.cluster.typed.SelfUp
import akka.cluster.{utils, Member}
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.slick.SlickProjection
import akka.projection.{ProjectionBehavior, ProjectionId}
import slick.basic.DatabaseConfig
import slick.jdbc.MySQLProfile

import scala.collection.immutable
import scala.concurrent.duration.DurationInt
import com.definition.domain.*
import com.definition.domain.Event as PbEvent

import java.util.UUID
import scala.concurrent.*

object Guardian {

  sealed trait Protocol
  object Protocol {
    final case class SelfUpMsg(mba: immutable.SortedSet[Member]) extends Protocol
  }

  val numberOfTags = 4
  val tags         = Vector.tabulate(numberOfTags)(_.toString)
  val name         = "events"

  private def mkProjection(
    tag: String,
    dbConfig: DatabaseConfig[MySQLProfile],
    name: String,
    region: ActorRef[com.definition.domain.Cmd]
  )(implicit system: ActorSystem[_]) = {
    // PersistenceQuery(system).readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)
    val resolver: ActorRefResolver = ActorRefResolver(system)
    implicit val to                = akka.util.Timeout(3.seconds)

    /*SlickProjection
      .groupedWithin(
        ProjectionId(name, tag),
        EventSourcedProvider.eventsByTag[PbEvent](system, JdbcReadJournal.Identifier, tag),
        dbConfig,
        () => ???
      )
      .withGroup(groupAfterEnvelopes = 10, groupAfterDuration = 300.millis)
     */

    SlickProjection
      .atLeastOnceAsync(
        ProjectionId(name, tag),
        // EventSourcedProvider.eventsBySlices()
        EventSourcedProvider.eventsByTag[PbEvent](system, JdbcReadJournal.Identifier, tag),
        dbConfig,
        () =>
          (env: akka.projection.eventsourced.EventEnvelope[PbEvent]) =>
            env.event match {
              case c: Created =>
                val row =
                  DefinitionIndexViewRow(
                    name = c.definition.name,
                    definition = c.definition,
                    ownerId = UUID.fromString(c.ownerId),
                    bucketId = env.persistenceId.toLong,
                    sequenceNr = c.seqNum,
                    when = env.timestamp
                  )

                Tables.definitionIndexView
                  .create(row)
                  .flatMap {
                    case true =>
                      Future.successful(Done)
                    case false =>
                      system.log.error(s"Create conflict detected by user ${row.ownerId.toString}")
                      region.askWithStatus[Done](replyTo =>
                        com.definition.domain
                          .Rollback(c.ownerId, c.rollbackLocation, resolver.toSerializationFormat(replyTo))
                      )
                  }(system.executionContext)

              case u: Updated =>
                val row =
                  DefinitionIndexViewRow(
                    name = u.definition.name,
                    definition = u.definition,
                    ownerId = UUID.fromString(u.ownerId),
                    bucketId = env.persistenceId.toLong,
                    sequenceNr = u.seqNum,
                    when = env.timestamp
                  )
                Tables.definitionIndexView.update(row)

              case ReleaseRequested(ownerId, prevDefinitionLocation, rollbackLocation) =>
                // Future.failed(new Exception(s"Boom !!!"))
                region.askWithStatus(replyTo =>
                  com.definition.domain
                    .Release(ownerId, prevDefinitionLocation, rollbackLocation, resolver.toSerializationFormat(replyTo))
                )

              case RollbackRequested(ownerId, rollbackLocation) =>
                region.askWithStatus(replyTo =>
                  com.definition.domain
                    .Rollback(ownerId, rollbackLocation, resolver.toSerializationFormat(replyTo))
                )

              case cmd: Released =>
                Tables.definitionIndexView.release(
                  cmd.prevDefinitionLocation.bucketId,
                  cmd.prevDefinitionLocation.seqNum
                )
            }
      )
      .withSaveOffset(afterEnvelopes = 10, afterDuration = 500.millis)
  }

  def initProjections(region: ActorRef[com.definition.domain.Cmd])(implicit system: ActorSystem[_]): Unit = {
    val dbConfig = DatabaseConfig.forConfig[MySQLProfile]("akka.projection.slick")
    ShardedDaemonProcess(system).init(
      name,
      numberOfTags,
      tag => ProjectionBehavior(mkProjection(tags(tag), dbConfig, name, region)),
      ShardedDaemonProcessSettings(system),
      Some(ProjectionBehavior.Stop)
    )
  }

  def apply(grpcPort: Int): Behavior[Nothing] =
    Behaviors
      .setup[Protocol] { ctx =>
        implicit val system            = ctx.system
        implicit val cluster           = akka.cluster.typed.Cluster(system)
        implicit val selfUniqueAddress = SelfUniqueAddress(cluster.selfMember.uniqueAddress)

        val selfAddress = selfUniqueAddress.uniqueAddress.address
        ctx.log.warn("★ ★ ★  Step 0. SelfUp: {}  ★ ★ ★", selfUniqueAddress)

        cluster.subscriptions.tell(
          akka.cluster.typed.Subscribe(
            ctx.messageAdapter[SelfUp] { case m: SelfUp =>
              Protocol.SelfUpMsg(immutable.SortedSet.from(m.currentClusterState.members)(Member.ageOrdering))
            },
            classOf[SelfUp]
          )
        )

        Behaviors
          .receive[Protocol] { case (ctx, _ @Protocol.SelfUpMsg(membersByAge)) =>
            cluster.subscriptions ! akka.cluster.typed.Unsubscribe(ctx.self)
            ctx.log.warn("★ ★ ★  Up: [{}]  ★ ★ ★", membersByAge.mkString(","))

            val shardingSettings = ClusterShardingSettings(system)
            val clusterSharding  = ClusterSharding(system)

            val takenDefinition: ActorRef[com.definition.domain.Cmd] =
              clusterSharding
                .init(
                  Entity(TakenDefinition.TypeKey)(TakenDefinition(_, snapshotEveryNEvents = 10))
                    .withMessageExtractor(TakenDefinition.Extractor(shardingSettings.numberOfShards))
                    .withStopMessage(com.definition.domain.Passivate())
                    .withAllocationStrategy(utils.newLeastShardAllocationStrategy())
                )

            val DDataShardReplicatorPath =
              RootActorPath(system.deadLetters.path.address) / "system" / "sharding" / "replicator"
            system.toClassic
              .actorSelection(DDataShardReplicatorPath)
              .resolveOne(5.seconds)
              .foreach { ddataShardReplicator =>
                akka.cluster.utils
                  .shardingStateChanges(ddataShardReplicator, cluster.selfMember.address.host.getOrElse("local"))
              }(system.executionContext)

            Tables.createAllTables()

            initProjections(takenDefinition /*.narrow[com.definition.domain.Release]*/ )

            Bootstrap(takenDefinition, selfAddress.host.get, grpcPort)(ctx.system)
            Behaviors.same
          }
      }
      .narrow
}
