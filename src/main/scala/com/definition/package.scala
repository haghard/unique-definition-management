package com.definition

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
