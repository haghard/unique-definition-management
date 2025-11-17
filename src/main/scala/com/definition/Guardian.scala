package com.definition

import org.apache.pekko.Done
import org.apache.pekko.actor.RootActorPath
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.TypedActorSystemOps
import org.apache.pekko.actor.typed.*
import org.apache.pekko.cluster.ddata.SelfUniqueAddress
import org.apache.pekko.cluster.sharding.typed.*
import org.apache.pekko.cluster.sharding.typed.scaladsl.*
import org.apache.pekko.cluster.typed.SelfUp
import org.apache.pekko
import slick.jdbc.MySQLProfile
import pekko.projection.ProjectionId
import pekko.projection.eventsourced.scaladsl.EventSourcedProvider
import pekko.projection.ProjectionBehavior

import scala.collection.immutable
import scala.concurrent.duration.DurationInt
import com.definition.domain.command.*
import com.definition.domain.event.*
import org.apache.pekko.cluster.*
import org.apache.pekko.persistence.jdbc.query.scaladsl.JdbcReadJournal
import org.apache.pekko.projection.slick.SlickProjection
import slick.basic.DatabaseConfig

import java.util.UUID

object Guardian {

  implicit val askTo: org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(6.seconds)

  sealed trait Protocol

  object Protocol {
    final case class SelfUpMsg(mba: immutable.SortedSet[Member]) extends Protocol
  }

  val numberOfTags   = 4
  val projectionName = TakenDefinition.TypeKey.name + ".proj"
  val tags           = Vector.tabulate(numberOfTags)(_.toString)

  def initProjections(takenDefinitions: ActorRef[Cmd])(implicit system: ActorSystem[_]): Unit = {
    implicit val resolver: ActorRefResolver = ActorRefResolver(system)
    val dbConfig                            = DatabaseConfig.forConfig[MySQLProfile]("pekko.projection.slick")

    ShardedDaemonProcess(system).init(
      projectionName,
      numberOfTags,
      i => {
        val projectionId   = ProjectionId.of(projectionName, tags(i))
        val sourceProvider = EventSourcedProvider.eventsByTag[Event](system, JdbcReadJournal.Identifier, tags(i))
        ProjectionBehavior(
          SlickProjection
            .atLeastOnceAsync(
              projectionId,
              sourceProvider,
              dbConfig,
              () =>
                (env: pekko.projection.eventsourced.EventEnvelope[Event]) =>
                  env.event match {
                    case a: Acquired =>
                      a.prevLocation match {
                        case Some(prev) =>
                          takenDefinitions.askWithStatus[Done](replyTo =>
                            com.definition.domain.command.Replace(
                              ownerId = a.ownerId,
                              location = a.location,
                              prevDefinitionLocation = prev,
                              replyTo = resolver.toSerializationFormat(replyTo)
                            )
                          )

                        case None =>
                          val row =
                            DefinitionIndexViewRow(
                              shardId = a.location.shardId,
                              definitionId = a.location.definitionId,
                              ownerId = UUID.fromString(a.ownerId),
                              when = env.timestamp
                            )
                          RelationalData.definitionIndexView.createAndUnlock(row)
                      }

                    case r: Released =>
                      val row =
                        DefinitionIndexViewRow(
                          shardId = r.newLocation.shardId,
                          definitionId = r.newLocation.definitionId,
                          ownerId = UUID.fromString(r.ownerId),
                          when = env.timestamp
                        )
                      RelationalData.definitionIndexView.updateAndUnlock(row /*r.prevLocation*/ )
                  }
            )
        )
      },
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

            val shardingSettings = ClusterShardingSettings(system)
            val clusterSharding  = ClusterSharding(system)

            val takenDefinition: ActorRef[Cmd] =
              clusterSharding
                .init(
                  Entity(TakenDefinition.TypeKey)(TakenDefinition(_, snapshotEveryNEvents = 10))
                    .withMessageExtractor(TakenDefinition.Extractor( /*shardingSettings.numberOfShards*/ ))
                    .withStopMessage(Passivate())
                    .withAllocationStrategy(utils.newLeastShardAllocationStrategy())
                )

            val DDataShardReplicatorPath =
              RootActorPath(system.deadLetters.path.address) / "system" / "sharding" / "replicator"
            system.toClassic
              .actorSelection(DDataShardReplicatorPath)
              .resolveOne(5.seconds)
              .foreach { ddataShardReplicator =>
                org.apache.pekko.cluster.utils
                  .shardingStateChanges(ddataShardReplicator, cluster.selfMember.address.host.getOrElse("local"))
              }(system.executionContext)

            RelationalData.createAllTables()
            initProjections(takenDefinition)
            Bootstrap(takenDefinition, selfAddress.host.get, grpcPort)(ctx.system)
            Behaviors.same
          }
      }
      .narrow
}
