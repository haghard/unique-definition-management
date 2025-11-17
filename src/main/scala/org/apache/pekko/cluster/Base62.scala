package org.apache.pekko.cluster

//(0L to 50_000_000L).foreach { i => if((i % 500_000) == 0) println(Base62.encode(i)) }
object Base62 {

  private val baseString: String = ((0 to 9) ++ ('A' to 'Z') ++ ('a' to 'z')).mkString
  private val base               = 62

  def decode(str: String): Long =
    str.zip(str.indices.reverse).map { case (c, p) => baseString.indexOf(c) * scala.math.pow(base, p).toLong }.sum

  def encode(i: Long): String = {
    // (BigDecimal(0) to BigDecimal("2500000000") by BigDecimal("100000000")).foreach { i => println(Base62.encode(i.toLong)) }
    @scala.annotation.tailrec
    def div(i: Long, res: List[Int] = Nil): List[Int] =
      (i / base) match {
        case q if q > 0 => div(q, (i % base).toInt :: res)
        case _          => i.toInt :: res
      }

    div(i).map(baseString(_)).mkString
  }
}
