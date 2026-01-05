package akka.cluster.ddata

import akka.actor.ExtendedActorSystem
import akka.cluster.ddata.protobuf.ReplicatedDataSerializer

final class CRDTSerializer(system: ExtendedActorSystem)
    extends ReplicatedDataSerializer(system)
    with akka.cluster.ddata.protobuf.SerializationSupport
    with ProtocDDataSupport {

  override def manifest(obj: AnyRef): String =
    super.manifest(obj)

  override def toBinary(obj: AnyRef): Array[Byte] =
    obj match {
      case reg: akka.cluster.ddata.LWWRegister[_] @unchecked =>
        reg.value match {
          // State from akka.cluster.sharding.DDataShardCoordinator
          case state: akka.cluster.sharding.ShardCoordinator.Internal.State =>
            system.log.warning("Shards online: {} ", state.shards.keySet.size)
          case _ =>
        }
        super.toBinary(obj)

      case orSet: ORSet[_] @unchecked =>
        // system.log.warning("ORSet({})", orSet.elements.mkString(","))
        super.toBinary(orSet)

      case oRMultiMap: ORMultiMap[_, _] =>
        // ORMultiMap(204)[ServiceKey[akka.actor.typed.internal.pubsub.TopicImpl$Command](r2dbc-taken-dfn-688),ServiceKey[akka.actor.typed.internal.pubsub.TopicImpl$Command](r2dbc-taken-dfn-953)...]

        system.log.warning(
          "ORMultiMap({}) [{}...]",
          oRMultiMap.underlying.keys.elements.size,
          oRMultiMap.underlying.keys.elements.take(2).mkString(",")
        )
        super.toBinary(oRMultiMap)

      case crdt =>
        system.log.warning("Other CRDT {}", crdt.getClass.getName)
        super.toBinary(obj)
    }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    super.fromBinary(bytes, manifest)
}
