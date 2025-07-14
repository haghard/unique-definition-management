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

        }
        super.toBinary(obj)

      case orSet: ORSet[_] @unchecked =>
        system.log.warning("ORSet({})", orSet.elements.mkString(","))
        super.toBinary(orSet)

      case crdt =>
        system.log.warning("Other CRDT {}", crdt)
        super.toBinary(obj)
    }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    super.fromBinary(bytes, manifest)
}
