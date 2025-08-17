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

final case class DefinitionIndexViewRow(
  name: String,
  definition: Definition,
  ownerId: UUID,
  bucketId: Long,
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

  class DefinitionIndexView(tag: Tag) extends Table[DefinitionIndexViewRow](tag, "definition_index_view") {

    // for debug only
    def name: Rep[String] = column[String]("NAME", O.Length(400))

    def definition: Rep[Definition] = column[Definition]("DEFINITION")

    def ownerId: Rep[UUID] = column[UUID]("OWNER_ID")

    // coordinates/location inside akka-sharding
    def bucketId: Rep[Long]   = column[Long]("BUCKET_ID")
    def sequenceNr: Rep[Long] = column[Long]("SEQ_NUM")
    // coordinates

    def when: Rep[Long] = column[Long]("WHEN")

    def pk: slick.lifted.PrimaryKey = primaryKey("DIV__PK", (bucketId, sequenceNr))

    def ownerIdIndex: slick.lifted.Index = index("DIV__OWNER_ID_IND", ownerId)

    def * : slick.lifted.ProvenShape[DefinitionIndexViewRow] =
      (name, definition, ownerId, bucketId, sequenceNr, when) <>
        ((DefinitionIndexViewRow.apply _).tupled, DefinitionIndexViewRow.unapply)
  }

  object definitionIndexView extends TableQuery(new DefinitionIndexView(_)) {
    self =>

    implicit val ec: scala.concurrent.ExecutionContext = ExecutionContext.parasitic

    val locationDefinition = Compiled { (ownerId: Rep[UUID]) =>
      self.filter(_.ownerId === ownerId).map(rep => (rep.bucketId, rep.sequenceNr, rep.definition))
    }

    def getLocationDefinition(ownerId: UUID): Future[scala.collection.immutable.Seq[(Long, Long, Definition)]] =
      db.run(locationDefinition(ownerId).result)

    def update(row: DefinitionIndexViewRow): Future[Done] =
      db.run(definitionIndexView.insertOrUpdate(row)).map(_ => Done)(ExecutionContext.parasitic)

    def conditionalCreate(row: DefinitionIndexViewRow): Future[Boolean] = {
      val dbio =
        definitionIndexView
          .filter(_.ownerId === row.ownerId)
          .map(rep => (rep.bucketId, rep.sequenceNr))
          .forUpdate
          .result
          .headOption
          .flatMap {
            case Some((bucketId, sequenceNr)) =>
              if (bucketId == row.bucketId && sequenceNr == row.sequenceNr)
                DBIO.successful(true)
              else
                DBIO.successful(false)
            case None =>
              definitionIndexView.insertOrUpdate(row).map(_ => true)
          }
          .transactionally

      db.run(dbio)
    }

    def releaseFailed(entityId: Long, seqNum: Long): Future[Done] =
      db.run(DBIO.from(Future.failed(new Exception(s"Boom($entityId,$seqNum) !!!"))))

    def release(bucketId: Long, seqNum: Long): Future[Done] =
      db
        .run(definitionIndexView.filter(rep => rep.bucketId === bucketId && rep.sequenceNr === seqNum).delete)
        .map(_ => Done)
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
