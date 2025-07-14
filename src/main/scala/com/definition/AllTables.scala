package com.definition

import akka.Done
import slick.jdbc.JdbcType

import scala.concurrent.*

final case class DefinitionOwnershipRow(
  name: String,
  address: String,
  city: String,
  country: String,
  state: Option[String],
  zipCode: Option[String],
  brand: Option[String],
  ownerId: String,
  persistenceId: String,
  sequenceNr: Long,
  when: Long
)

class SlickTablesGeneric(val profile: slick.jdbc.MySQLProfile) {

  import profile.api._

  def safeEquals[A: JdbcType](rep: Rep[Option[A]], vOpt: Option[A]): Rep[Option[Boolean]] =
    vOpt match {
      case Some(v) => rep === v
      case None    => rep.isEmpty.?
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

    def persistenceId: Rep[String] = column[String]("P_ID", O.Length(50))

    def sequenceNr: Rep[Long] = column[Long]("SEQ_NUM")

    def when: Rep[Long] = column[Long]("WHEN")

    def ind: slick.lifted.Index = index("OWNERSHIP__IND", (name, address, city))

    def ownerIdIndex: slick.lifted.Index = index("OWNERSHIP__OWNER_ID_IND", ownerId)

    def * : slick.lifted.ProvenShape[DefinitionOwnershipRow] =
      (name, address, city, country, state, zipCode, brand, ownerId, persistenceId, sequenceNr, when) <>
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
      Connection.db.run(ownership.+=(row)).map(_ => Done)(ExecutionContext.parasitic)

    private def query(
      name: String,
      address: String,
      city: String,
      country: String,
      state: Option[String],
      zipCode: Option[String],
      brand: Option[String],
      ownerId: String
    ): Query[Ownership, DefinitionOwnershipRow, Seq] =
      ownership.filter(rep =>
        rep.ownerId === ownerId &&
          rep.name === name && rep.address === address && rep.city === city &&
          rep.country === country && safeEquals(rep.state, state) && safeEquals(rep.zipCode, zipCode)
          && safeEquals(rep.brand, brand)
      )

    def release(
      name: String,
      address: String,
      city: String,
      country: String,
      state: Option[String],
      zipCode: Option[String],
      brand: Option[String],
      ownerId: String
    ): Future[Done] =
      Connection.db
        .run(query(name, address, city, country, state, zipCode, brand, ownerId).delete)
        .map(_ => Done)(ExecutionContext.parasitic)
  }

  val tables           = Seq(ownership)
  val ddl: profile.DDL = tables.map(_.schema).reduce(_ ++ _)

  def createAllTables() =
    Connection.db.run(ddl.createIfNotExists)

}

object AllTables extends SlickTablesGeneric(slick.jdbc.MySQLProfile)
