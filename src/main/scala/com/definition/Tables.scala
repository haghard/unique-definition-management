package com.definition

import akka.Done
import slick.basic.DatabaseConfig
import slick.jdbc.{GetResult, MySQLProfile}

import java.nio.ByteBuffer
import java.util.UUID
import scala.concurrent.*
import scala.util.Using

final case class DefinitionOwnershipRow(
  name: String,
  address: String,
  city: String,
  country: String,
  state: Option[String],
  zipCode: Option[String],
  brand: Option[String],
  ownerId: String,
  entityId: Int,
  sequenceNr: Long,
  when: Long
)

class SlickTablesGeneric(val profile: slick.jdbc.MySQLProfile) {

  import profile.api._

  implicit val _: GetResult[UUID] = GetResult { rs =>
    val bts = ByteBuffer.wrap(rs.nextBytes())
    new UUID(bts.getLong(), bts.getLong())
  }

  class Ownership(tag: Tag) extends Table[DefinitionOwnershipRow](tag, "OWNERSHIP") {

    def name: Rep[String] = column[String]("NAME", O.Length(255))

    def address: Rep[String] = column[String]("ADDRESS", O.Length(255))

    def city: Rep[String] = column[String]("CITY", O.Length(255))

    def country: Rep[String] = column[String]("COUNTRY", O.Length(255))

    def state: Rep[Option[String]] = column[Option[String]]("STATE", O.Length(255))

    def zipCode: Rep[Option[String]] = column[Option[String]]("ZIP_CODE", O.Length(255))

    def brand: Rep[Option[String]] = column[Option[String]]("BRAND", O.Length(255))

    def ownerId: Rep[String] = column[String]("OWNER_ID", O.Length(36))

    def entityId: Rep[Int] = column[Int]("ENTITY_ID")

    def sequenceNr: Rep[Long] = column[Long]("SEQ_NUM")

    def when: Rep[Long] = column[Long]("WHEN")

    def pk: slick.lifted.PrimaryKey = primaryKey("OWNERSHIP__PK", (entityId, sequenceNr))

    def ownerIdIndex: slick.lifted.Index = index("OWNERSHIP__OWNER_ID_IND", ownerId)

    def * : slick.lifted.ProvenShape[DefinitionOwnershipRow] =
      (name, address, city, country, state, zipCode, brand, ownerId, entityId, sequenceNr, when) <>
        ((DefinitionOwnershipRow.apply _).tupled, DefinitionOwnershipRow.unapply)
  }

  object ownership extends TableQuery(new Ownership(_)) {
    self =>

    val getByOwnerId = Compiled { (ownerId: Rep[String]) =>
      self.filter(_.ownerId === ownerId)
    }

    val getByName = Compiled { (name: Rep[String]) =>
      self.filter(_.name === name)
    }

    def acquire(row: DefinitionOwnershipRow): Future[Done] =
      db.run(ownership.+=(row)).map(_ => Done)(ExecutionContext.parasitic)

    def release(entityId: Int, seqNum: Long): Future[Done] =
      db
        .run(ownership.filter(rep => rep.entityId === entityId && rep.sequenceNr === seqNum).delete)
        .map(_ => Done)(ExecutionContext.parasitic)
  }

  val tables           = Seq(ownership)
  val ddl: profile.DDL = tables.map(_.schema).reduce(_ ++ _)

  val db = {
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

  def createAllTables(): Future[Unit] =
    db.run(ddl.createIfNotExists)

}

object Tables extends SlickTablesGeneric(slick.jdbc.MySQLProfile)
