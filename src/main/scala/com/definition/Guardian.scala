package com.definition

import org.apache.pekko.Done
import org.apache.pekko.actor.RootActorPath
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.TypedActorSystemOps
import org.apache.pekko.actor.typed.{ActorRef, ActorRefResolver, ActorSystem, Behavior}
import org.apache.pekko.cluster.ddata.SelfUniqueAddress
import org.apache.pekko.cluster.sharding.typed.{ClusterShardingSettings, ShardedDaemonProcessSettings}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, ShardedDaemonProcess}
import org.apache.pekko.cluster.typed.SelfUp
import org.apache.pekko
import org.apache.pekko.persistence.query.{Offset, PersistenceQuery, TimestampOffset}
import pekko.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import pekko.projection.r2dbc.scaladsl.R2dbcProjection
import pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import pekko.projection.ProjectionId
import pekko.projection.eventsourced.scaladsl.EventSourcedProvider
import pekko.projection.Projection
import pekko.projection.ProjectionBehavior
import pekko.projection.scaladsl.{Handler, SourceProvider}
import pekko.persistence.query.typed.EventEnvelope
import org.apache.pekko.persistence.query.typed.EventEnvelope

import scala.collection.immutable
import scala.concurrent.duration.DurationInt
import com.definition.domain.command.*
import com.definition.domain.event.*
import org.apache.pekko.cluster.{utils, Member}
import org.apache.pekko.persistence.Persistence
import org.apache.pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import org.apache.pekko.serialization.{Serialization, SerializationExtension}

import scala.concurrent.Future
//import slick.basic.DatabaseConfig
//import slick.jdbc.MySQLProfile
//import slick.jdbc.PostgresProfile

import java.util.UUID

object Guardian {

  implicit val askTo: org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(4.seconds)

  sealed trait Protocol

  object Protocol {
    final case class SelfUpMsg(mba: immutable.SortedSet[Member]) extends Protocol
  }

  val numberOfTags = 4
  // val tags         = Vector.tabulate(numberOfTags)(_.toString)
  // val name         = "events"

  private def mkProjection(
    idx: Int,
    sliceRanges: immutable.Seq[Range],
    takenDefinitions: ActorRef[Cmd]
  )(implicit system: ActorSystem[_]) = {
    val resolver: ActorRefResolver = ActorRefResolver(system)

    val sliceRange    = sliceRanges(idx)
    val projectionKey = s"${sliceRange.min}-${sliceRange.max}"
    val projectionId  = ProjectionId.of(TakenDefinition.TypeKey.name, projectionKey)

    // import pekko.projection.scaladsl.ProjectionManagement
    // ProjectionManagement(system)
    // ProjectionManagement(system).resume(projectionId)
    // .getOffset[Offset](projectionId)
    // .onComplete(r => println(s"$projectionId : $r"))(system.executionContext)

    val minSlice                                         = sliceRanges.head.min
    val maxSlice                                         = sliceRanges.head.max
    val entityType: String                               = TakenDefinition.TypeKey.name
    val sp: SourceProvider[Offset, EventEnvelope[Event]] =
      EventSourcedProvider.eventsBySlices[Event](system, R2dbcReadJournal.Identifier, entityType, minSlice, maxSlice)

    // TimestampOffset
    R2dbcProjection
      .atLeastOnceAsync[Offset, EventEnvelope[Event]](
        projectionId,
        settings = None,
        sp,
        handler = () =>
          new Handler[EventEnvelope[Event]]() {
            override def process(envelope: EventEnvelope[Event]): Future[Done] =
              Future.successful {
                println("***" + envelope.persistenceId);
                Done
              }
          } // new EventHandler(resolver, takenDefinitions)(system)
      )
  }

  def initProjections(region: ActorRef[Cmd])(implicit system: ActorSystem[_]): Unit = {
    // val dbConfig = DatabaseConfig.forConfig[ MySQLProfile]("akka.projection.slick")
    // val dbConfig = DatabaseConfig.forConfig[slick.jdbc.PostgresProfile]("akka.projection.slick")

    val sliceRanges = EventSourcedProvider.sliceRanges(system, R2dbcReadJournal.Identifier, numberOfTags)

    // val sliceRanges = Persistence(system).sliceRanges(numberOfTags)
    ShardedDaemonProcess(system).init(
      TakenDefinition.TypeKey.name,
      numberOfTags,
      i => ProjectionBehavior(mkProjection(i, sliceRanges, region)),
      ShardedDaemonProcessSettings(system),
      Some(ProjectionBehavior.Stop)
    )
  }

  def apply(grpcPort: Int): Behavior[Nothing] =
    Behaviors
      .setup[Protocol] { ctx =>
        implicit val system            = ctx.system
        implicit val cluster           = org.apache.pekko.cluster.typed.Cluster(system)
        implicit val selfUniqueAddress = SelfUniqueAddress(cluster.selfMember.uniqueAddress)

        val selfAddress = selfUniqueAddress.uniqueAddress.address
        ctx.log.warn("★ ★ ★  Step 0. SelfUp: {}  ★ ★ ★", selfUniqueAddress)

        cluster.subscriptions.tell(
          org.apache.pekko.cluster.typed.Subscribe(
            ctx.messageAdapter[SelfUp] { case m: SelfUp =>
              Protocol.SelfUpMsg(immutable.SortedSet.from(m.currentClusterState.members)(Member.ageOrdering))
            },
            classOf[SelfUp]
          )
        )

        Behaviors
          .receive[Protocol] { case (ctx, _ @Protocol.SelfUpMsg(membersByAge)) =>
            cluster.subscriptions ! org.apache.pekko.cluster.typed.Unsubscribe(ctx.self)
            ctx.log.warn("★ ★ ★  Up: [{}]  ★ ★ ★", membersByAge.mkString(","))

            /*println(
              SerializationExtension(ctx.system).serializerFor(classOf[com.definition.domain.event.Acquired]).identifier
            )*/

            val shardingSettings = ClusterShardingSettings(system)
            val clusterSharding  = ClusterSharding(system)

            val takenDefinition: ActorRef[Cmd] =
              clusterSharding
                .init(
                  Entity(TakenDefinition.TypeKey)(TakenDefinition(_, snapshotEveryNEvents = 10))
                    .withMessageExtractor(TakenDefinition.Extractor(shardingSettings.numberOfShards))
                    .withStopMessage(Passivate())
                    .withAllocationStrategy(utils.newLeastShardAllocationStrategy())
                )

            /*val DDataShardReplicatorPath =
              RootActorPath(system.deadLetters.path.address) / "system" / "sharding" / "replicator"
            system.toClassic
              .actorSelection(DDataShardReplicatorPath)
              .resolveOne(5.seconds)
              .foreach { ddataShardReplicator =>
                org.apache.pekko.cluster.utils
                  .shardingStateChanges(ddataShardReplicator, cluster.selfMember.address.host.getOrElse("local"))
              }(system.executionContext)*/

            // RelationalData.createAllTables()

            initProjections(takenDefinition)
            Bootstrap(takenDefinition, selfAddress.host.get, grpcPort)(ctx.system)
            Behaviors.same
          }
      }
      .narrow
}
