package com.definition

import akka.Done
import akka.actor.typed.ActorSystem
import akka.projection.slick.SlickProjection
import com.definition.domain.Definition
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}
import slick.basic.DatabaseConfig
import slick.jdbc.{GetResult, MySQLProfile}

import java.util.UUID
import scala.concurrent.*
import scala.reflect.ClassTag
import scala.util.Using

final case class DefinitionOwnershipRow(
  name: String,
  definition: Definition,
  /*address: String,
  city: String,
  country: String,
  state: Option[String],
  zipCode: Option[String],*/
  ownerId: String,
  entityId: Long,
  sequenceNr: Long,
  when: Long
)

class SlickTablesGeneric(val profile: slick.jdbc.MySQLProfile) {

  import profile.api._

  implicit val GetResultUuid: GetResult[UUID] = slick.jdbc.GetResult { rs =>
    val bts = java.nio.ByteBuffer.wrap(rs.nextBytes())
    new java.util.UUID(bts.getLong(), bts.getLong())
  }

  implicit def pbMapper[T <: GeneratedMessage: ClassTag](implicit
    companion: GeneratedMessageCompanion[T]
  ): BaseColumnType[T] =
    MappedColumnType.base((pb: T) => pb.toByteArray, (bts: Array[Byte]) => companion.parseFrom(bts))

  class Ownership(tag: Tag) extends Table[DefinitionOwnershipRow](tag, "OWNERSHIP") {

    def name: Rep[String] = column[String]("NAME", O.Length(400))

    /*
    def address: Rep[String] = column[String]("ADDRESS", O.Length(255))
    def city: Rep[String] = column[String]("CITY", O.Length(255))
    def country: Rep[String] = column[String]("COUNTRY", O.Length(255))
    def state: Rep[Option[String]] = column[Option[String]]("STATE", O.Length(255))
    def zipCode: Rep[Option[String]] = column[Option[String]]("ZIP_CODE", O.Length(255))
     */

    def definition: Rep[Definition] = column[Definition]("DEFINITION")

    def ownerId: Rep[String] = column[String]("OWNER_ID", O.Length(36))

    def takenDefinitionEntityId: Rep[Long] = column[Long]("ENTITY_ID")

    def sequenceNr: Rep[Long] = column[Long]("SEQ_NUM")

    def when: Rep[Long] = column[Long]("WHEN")

    def pk: slick.lifted.PrimaryKey = primaryKey("OWNERSHIP__PK", (takenDefinitionEntityId, sequenceNr))

    def ownerIdIndex: slick.lifted.Index = index("OWNERSHIP__OWNER_ID_IND", ownerId)

    def * : slick.lifted.ProvenShape[DefinitionOwnershipRow] =
      (
        name,
        definition, /*address, city, country, state, zipCode, brand,*/ ownerId,
        takenDefinitionEntityId,
        sequenceNr,
        when
      ) <>
        ((DefinitionOwnershipRow.apply _).tupled, DefinitionOwnershipRow.unapply)
  }

  object ownership extends TableQuery(new Ownership(_)) {
    self =>

    val getDefinitionOwnerId = Compiled { (ownerId: Rep[String]) =>
      self.filter(_.ownerId === ownerId).map(rep => (rep.takenDefinitionEntityId, rep.sequenceNr, rep.definition))
    }

    val getLocationByOwnerId = Compiled { (ownerId: Rep[String]) =>
      self.filter(_.ownerId === ownerId).map(rep => (rep.takenDefinitionEntityId, rep.sequenceNr))
    }

    def locationByOwnerId(ownerId: String): Future[scala.collection.immutable.Seq[(Long, Long)]] =
      db.run(getLocationByOwnerId(ownerId).result)

    def definitionByOwnerId(ownerId: String): Future[scala.collection.immutable.Seq[(Long, Long, Definition)]] =
      db.run(getDefinitionOwnerId(ownerId).result)

    def acquire(row: DefinitionOwnershipRow): Future[Done] =
      db.run(ownership.insertOrUpdate(row)).map(_ => Done)(ExecutionContext.parasitic)

    def releaseFailed(entityId: Long, seqNum: Long): Future[Done] =
      db.run(DBIO.from(Future.failed(new Exception(s"Boom($entityId,$seqNum) !!!"))))

    def release(entityId: Long, seqNum: Long): Future[Done] =
      db
        .run(ownership.filter(rep => rep.takenDefinitionEntityId === entityId && rep.sequenceNr === seqNum).delete)
        .map(_ => Done)(ExecutionContext.parasitic)
  }

  val tables           = Seq(ownership)
  val ddl: profile.DDL = tables.map(_.schema).reduce(_ ++ _)

  private val dbConfig = DatabaseConfig.forConfig[MySQLProfile]("akka.projection.slick")
  val db               = {
    val local = dbConfig.db
    val md    = local.source.createConnection().getMetaData()
    (1 to 8).foreach(i => println(s"Supports $i = " + md.supportsTransactionIsolationLevel(i)))
    Using.resource(local.source.createConnection()) { con =>
      println("Active TransactionIsolation:" + con.getTransactionIsolation()) // 4 - TRANSACTION_REPEATABLE_READ
      con.close()
    }
    local
  }

  def createAllTables()(implicit sys: ActorSystem[_]): Future[Done] =
    db.run(ddl.createIfNotExists)
      .flatMap(_ => SlickProjection.createTablesIfNotExists(dbConfig))(ExecutionContext.parasitic)
}

object Tables extends SlickTablesGeneric(slick.jdbc.MySQLProfile)
