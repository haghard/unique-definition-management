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
  ownerId: UUID,
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

    def name: Rep[String] = column[String]("NAME", O.Length(255))

    def definition: Rep[Definition] = column[Definition]("DEFINITION")

    def ownerId: Rep[UUID] = column[UUID]("OWNER_ID")

    def entityId: Rep[Long] = column[Long]("ENTITY_ID")

    def sequenceNr: Rep[Long] = column[Long]("SEQ_NUM")

    def when: Rep[Long] = column[Long]("WHEN")

    def pk: slick.lifted.PrimaryKey = primaryKey("OWNERSHIP__PK", (entityId, sequenceNr))

    def ownerIdIndex: slick.lifted.Index = index("OWNERSHIP__OWNER_ID_IND", ownerId)

    def * : slick.lifted.ProvenShape[DefinitionOwnershipRow] =
      (name, definition, ownerId, entityId, sequenceNr, when) <>
        ((DefinitionOwnershipRow.apply _).tupled, DefinitionOwnershipRow.unapply)
  }

  object ownership extends TableQuery(new Ownership(_)) {
    self =>

    val locationByOwnerId = Compiled { (ownerId: Rep[UUID]) =>
      self.filter(_.ownerId === ownerId).map(rep => (rep.entityId, rep.sequenceNr))
    }

    def getLocationByOwnerId(ownerId: UUID): Future[scala.collection.immutable.Seq[(Long, Long)]] =
      db.run(locationByOwnerId(ownerId).result)

    def acquire(row: DefinitionOwnershipRow): Future[Done] =
      db.run(ownership.insertOrUpdate(row)).map(_ => Done)(ExecutionContext.parasitic)

    def releaseFailed(entityId: Long, seqNum: Long): Future[Done] =
      db.run(DBIO.from(Future.failed(new Exception(s"Boom($entityId,$seqNum) !!!"))))

    def release(entityId: Long, seqNum: Long): Future[Done] =
      db
        .run(ownership.filter(rep => rep.entityId === entityId && rep.sequenceNr === seqNum).delete)
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
