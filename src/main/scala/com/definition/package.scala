package com.definition

object Implicits {

  implicit class Ops(val definition: com.definition.domain.Definition) extends AnyVal {

    def contentKey: String =
      definition.name + definition.address + definition.city + definition.country + definition.state.getOrElse(
        "n"
      ) + definition.zipCode.getOrElse(
        "n"
      ) + definition.brand.getOrElse("n")
  }
}
