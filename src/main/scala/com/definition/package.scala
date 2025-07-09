package com.definition

object Implicits {

  implicit class Ops(val self: com.definition.domain.Definition) extends AnyVal {
    def contentKey: String =
      //self.toProtoString
      self.name + self.address + self.city + self.country + self.state + self.zipCode + self.brand
  }
}
