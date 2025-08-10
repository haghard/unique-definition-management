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
  causalToken: Long,
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

  class DefinitionIndexView(tag: Tag) extends Table[DefinitionOwnershipRow](tag, "definition_index_view") {

    def name: Rep[String] = column[String]("NAME", O.Length(255))

    def definition: Rep[Definition] = column[Definition]("DEFINITION")

    def ownerId: Rep[UUID] = column[UUID]("OWNER_ID")

    def entityId: Rep[Long] = column[Long]("ENTITY_ID")

    def sequenceNr: Rep[Long] = column[Long]("SEQ_NUM")

    def causalToken: Rep[Long] = column[Long]("CAUSAL_TOKEN")

    def when: Rep[Long] = column[Long]("WHEN")

    def pk: slick.lifted.PrimaryKey = primaryKey("OWNERSHIP__PK", (entityId, sequenceNr))

    def ownerIdIndex: slick.lifted.Index = index("OWNERSHIP__OWNER_ID_IND", ownerId)

    def * : slick.lifted.ProvenShape[DefinitionOwnershipRow] =
      (name, definition, ownerId, entityId, sequenceNr, causalToken, when) <>
        ((DefinitionOwnershipRow.apply _).tupled, DefinitionOwnershipRow.unapply)
  }

  object definitionIndexView extends TableQuery(new DefinitionIndexView(_)) {
    self =>

    val locationByOwnerId = Compiled { (ownerId: Rep[UUID]) =>
      self.filter(_.ownerId === ownerId).map(rep => (rep.entityId, rep.sequenceNr, rep.definition, rep.causalToken))
    }

    val GetCausalToken = Compiled { (ownerId: Rep[UUID]) =>
      self.filter(_.ownerId === ownerId).map(rep => rep.causalToken)
    }

    def getLocationByOwnerId(ownerId: UUID): Future[scala.collection.immutable.Seq[(Long, Long, Definition, Long)]] =
      db.run(locationByOwnerId(ownerId).result)

    def getCausalToken(ownerId: UUID): Future[Option[Long]] =
      db.run(GetCausalToken(ownerId).result.headOption)

    def acquire(row: DefinitionOwnershipRow): Future[Done] =
      db.run(definitionIndexView.insertOrUpdate(row)).map(_ => Done)(ExecutionContext.parasitic)

    def releaseFailed(entityId: Long, seqNum: Long): Future[Done] =
      db.run(DBIO.from(Future.failed(new Exception(s"Boom($entityId,$seqNum) !!!"))))

    def release(entityId: Long, seqNum: Long): Future[Done] =
      db
        .run(definitionIndexView.filter(rep => rep.entityId === entityId && rep.sequenceNr === seqNum).delete)
        .map(_ => Done)(ExecutionContext.parasitic)
  }

  val tables           = Seq(definitionIndexView)
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
