package com.definition

object Implicits {

  implicit class Ops(val self: com.definition.domain.Definition) extends AnyVal {

    def contentKey: String =
      self.name + self.address + self.city + self.country + self.state.getOrElse("n") + self.zipCode.getOrElse(
        "n"
      ) + self.brand.getOrElse("n")
  }
}
