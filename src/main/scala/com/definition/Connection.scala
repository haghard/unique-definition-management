package com.definition

import slick.basic.DatabaseConfig
import slick.jdbc.MySQLProfile

import scala.util.Using

object Connection {
  val db = {
    // Database.forConfig("akka-persistence-jdbc.shared-databases.slick.db")
    val dbConfig = DatabaseConfig.forConfig[MySQLProfile]("akka.projection.slick")
    val local    = dbConfig.db
    val md       = local.source.createConnection().getMetaData()
    (1 to 8).foreach(i => println(s"Supports $i = " + md.supportsTransactionIsolationLevel(i)))
    Using.resource(local.source.createConnection()) { con =>
      println("Active TransactionIsolation:" + con.getTransactionIsolation()) // 4 - TRANSACTION_REPEATABLE_READ
      con.close()
    }
    local
  }
}
