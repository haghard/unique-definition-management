package com.definition

import akka.Done
import akka.actor.RootActorPath
import akka.actor.typed.scaladsl.AskPattern.{schedulerFromActorSystem, Askable}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import akka.actor.typed.{ActorRef, ActorRefResolver, ActorSystem, Behavior}
import akka.cluster.ddata.SelfUniqueAddress
import akka.cluster.sharding.typed.{ClusterShardingSettings, ShardedDaemonProcessSettings}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, ShardedDaemonProcess}
import akka.cluster.typed.SelfUp
import akka.cluster.Member
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.slick.SlickProjection
import akka.projection.{ProjectionBehavior, ProjectionId}
import slick.basic.DatabaseConfig
import slick.jdbc.MySQLProfile

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import com.definition.domain.*
import com.definition.domain.Event as PbEvent

object Guardian {

  sealed trait Protocol
  object Protocol {
    final case class SelfUpMsg(mba: immutable.SortedSet[Member]) extends Protocol
  }

  val numberOfTags = 4
  val tags         = Vector.tabulate(numberOfTags)(_.toString)
  val name         = "taken-definition-events"

  private def mkProjection(
    tag: String,
    dbConfig: DatabaseConfig[MySQLProfile],
    name: String,
    region: ActorRef[com.definition.domain.Release]
  )(implicit system: ActorSystem[_]) = {
    // PersistenceQuery(system).readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)
    val resolver: ActorRefResolver = ActorRefResolver(system)
    implicit val to                = akka.util.Timeout(3.seconds)
    SlickProjection
      .atLeastOnceAsync(
        ProjectionId(name, tag),
        EventSourcedProvider.eventsByTag[PbEvent](system, JdbcReadJournal.Identifier, tag),
        dbConfig,
        () =>
          (envelope: akka.projection.eventsourced.EventEnvelope[PbEvent]) =>
            envelope.event match {
              case _: Acquired =>
                Future.successful(Done)
              case _: Released =>
                Future.successful(Done)
              case ReleaseRequested(ownerId, definition) =>
                region.askWithStatus(replyTo =>
                  com.definition.domain.Release(ownerId, definition, resolver.toSerializationFormat(replyTo))
                )
            }
      )
  }

  def initProjections(region: ActorRef[com.definition.domain.Release])(implicit system: ActorSystem[_]): Unit = {
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

            // val commonSettings  = ClusterShardingSettings(system)
            val clusterSharding                             = ClusterSharding(system)
            val region: ActorRef[com.definition.domain.Cmd] =
              clusterSharding
                .init(
                  Entity(TakenDefinitionBucket.TypeKey)(TakenDefinitionBucket(_))
                    .withMessageExtractor(TakenDefinitionBucket.Extractor())
                    .withStopMessage(com.definition.domain.Passivate())
                    .withAllocationStrategy(
                      akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
                        .leastShardAllocationStrategy(TakenDefinitionBucket.NumOfShards / 2, 0.2)
                    )
                    .withSettings(
                      ClusterShardingSettings(system)
                        .withPassivationStrategy(
                          akka.cluster.sharding.typed.ClusterShardingSettings.PassivationStrategySettings.defaults
                            .withIdleEntityPassivation(30.seconds)
                        )
                    )
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

            initProjections(region.narrow[com.definition.domain.Release])
            Bootstrap(region, selfAddress.host.get, grpcPort)(ctx.system)
            Behaviors.same
          }
      }
      .narrow
}
