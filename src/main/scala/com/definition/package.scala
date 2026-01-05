package com.definition

import com.definition.api.PutRequest
import com.definition.domain.DefinitionLocation

object tables {

  def definitionTableByOwner(tables: Vector[String], ownerId: String) =
    tables(math.abs(ownerId.hashCode() % tables.size))
}

object Implicits {

  implicit class Ops(val self: com.definition.domain.Definition) extends AnyVal {

    def contentKey: String =
      self.name + self.address + self.city + self.country + self.state.getOrElse("n") + self.zipCode.getOrElse(
        "n"
      ) + self.brand.getOrElse("n")
  }
}

sealed trait RequestResult

object RequestResult {
  final case class Ok(definitionLocation: DefinitionLocation) extends RequestResult

  final case object Update extends RequestResult

  final case object Placed extends RequestResult

  final case class Resend(request: PutRequest) extends RequestResult

  final case class ResendInFlightRequestOnConflict(request: PutRequest) extends RequestResult

  final case object ConcurrentModification extends RequestResult

  final case class OwnerReserved(entityId: Long, seqNum: Long) extends RequestResult

  final case object LocationNotFound extends RequestResult

  final case object NotFound extends RequestResult
}
