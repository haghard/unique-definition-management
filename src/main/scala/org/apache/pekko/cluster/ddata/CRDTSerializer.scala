package org.apache.pekko.cluster.ddata

import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.cluster.ddata.*
import org.apache.pekko.cluster.ddata.protobuf.ReplicatedDataSerializer

final class CRDTSerializer(system: ExtendedActorSystem)
    extends ReplicatedDataSerializer(system)
    with org.apache.pekko.cluster.ddata.protobuf.SerializationSupport
    with ProtocDDataSupport {

  override def manifest(obj: AnyRef): String =
    super.manifest(obj)

  override def toBinary(obj: AnyRef): Array[Byte] =
    obj match {
      case reg: LWWRegister[_] @unchecked =>
        reg.value match {
          // State from akka.cluster.sharding.DDataShardCoordinator
          case state: org.apache.pekko.cluster.sharding.ShardCoordinator.Internal.State =>
            system.log.warning("Shards online: {} ", state.shards.keySet.size)
          case _ =>
        }
        super.toBinary(obj)

      case orSet: ORSet[_] @unchecked =>
        system.log.warning("ORSet({})", orSet.elements.mkString(","))
        super.toBinary(orSet)

      // case orMap: org.apache.pekko.cluster.ddata.ORMultiMap[_,_] =>
      case crdt =>
        system.log.warning("Other CRDT {}", crdt)
        super.toBinary(obj)
    }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    super.fromBinary(bytes, manifest)
}
