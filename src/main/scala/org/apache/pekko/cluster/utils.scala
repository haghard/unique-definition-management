package org.apache.pekko.cluster

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter.TypedActorRefOps
import org.apache.pekko.cluster.ddata.{LWWRegister, LWWRegisterKey, Replicator}
import org.apache.pekko.cluster.sharding.ShardCoordinator
import org.apache.pekko.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import org.apache.pekko.stream.{ActorAttributes, CompletionStrategy, KillSwitch, KillSwitches, OverflowStrategy, Supervision}
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink}
import org.apache.pekko.stream.typed.scaladsl.ActorSource
import com.definition.TakenDefinition

import scala.util.control.NonFatal

object utils {

  def newLeastShardAllocationStrategy() = {
    val leastShardAllocationNew: org.apache.pekko.cluster.sharding.internal.LeastShardAllocationStrategy =
      ShardAllocationStrategy
        .leastShardAllocationStrategy(3, 1)
        .asInstanceOf[org.apache.pekko.cluster.sharding.internal.LeastShardAllocationStrategy]
    leastShardAllocationNew
  }

  val typeName: String    = TakenDefinition.TypeKey.name
  val CoordinatorStateKey = LWWRegisterKey[ShardCoordinator.Internal.State](s"${typeName}CoordinatorState")

  def shardingStateChanges(ddataShardReplicator: ActorRef, selfHost: String)(implicit
    sys: ActorSystem[_]
  ): KillSwitch = {
    val actorWatchingFlow =
      Flow[String]
        .watch(ddataShardReplicator)
        .buffer(1, OverflowStrategy.backpressure)

    type ShardCoordinatorState = LWWRegister[org.apache.pekko.cluster.sharding.ShardCoordinator.Internal.State]
    val (actorSource, src) =
      ActorSource
        .actorRef[Replicator.SubscribeResponse[ShardCoordinatorState]](
          completionMatcher = { case _: Replicator.Deleted[ShardCoordinatorState] =>
            CompletionStrategy.draining
          },
          failureMatcher = PartialFunction.empty,
          1,
          OverflowStrategy.dropHead
        )
        .preMaterialize()

    ddataShardReplicator ! Replicator.Subscribe(CoordinatorStateKey, actorSource.toClassic)

    src
      .collect { case value @ Replicator.Changed(_) =>
        val shardCoordinatorState: ShardCoordinator.Internal.State = value.get(CoordinatorStateKey).value
        new StringBuilder()
          .append("\n")
          // .append("Shards: [")
          // .append(state.shards.keySet.mkString(","))
          // .append(state.shards.mkString(","))
          // .append(state.shards.map { case (k, ar) => s"$k:${ar.path.address.host.getOrElse(selfHost)}" }.mkString(","))
          // .append("]")
          // .append("\n")
          .append(s"ShardCoordinatorState($selfHost) updated [ ")
          .append(
            shardCoordinatorState.regions
              .map { case (sr, shards) => s"${sr.path.address.host.getOrElse(selfHost)}:[${shards.mkString(",")}]" }
              .mkString(", ")
          )
          .append(" ]")
          .toString()
      }
      .via(actorWatchingFlow)
      .viaMat(KillSwitches.single)(Keep.right)
      .to(Sink.foreach(stateLine => sys.log.warn(stateLine)))
      .withAttributes(
        ActorAttributes.supervisionStrategy {
          case ex: org.apache.pekko.stream.WatchedActorTerminatedException =>
            sys.log.error("Replicator failed. Terminate stream", ex)
            Supervision.Stop
          case NonFatal(ex) =>
            sys.log.error("Unexpected error!", ex)
            Supervision.Stop
        }
      )
      .run()
  }
}
