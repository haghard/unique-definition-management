package com.definition

import com.github.fzakaria.ascii85.Ascii85

import java.nio.charset.StandardCharsets

object Implicits {

  implicit class Ops(val self: com.definition.domain.Definition) extends AnyVal {

    def contentKey: String =
      self.name + self.address + self.city + self.country + self.state.getOrElse("n") + self.zipCode.getOrElse(
        "n"
      ) + self.brand.getOrElse("n")

    def ascii85: String =
      Ascii85.encode(contentKey.getBytes(StandardCharsets.UTF_8))
  }
}
