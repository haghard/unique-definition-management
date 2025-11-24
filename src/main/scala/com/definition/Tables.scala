package com.definition

import akka.Done
import akka.actor.typed.ActorSystem
import akka.projection.slick.SlickProjection
import com.definition.api.{PutReply, PutRequest}
import com.definition.domain.{Definition, DefinitionLocation}
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
  isLocked: Boolean = false,
  when: Long
)

final case class TemporalConstraintRow(ownerId: UUID, when: Long)

class SlickTablesGeneric(val profile: slick.jdbc.MySQLProfile) {

  import profile.api._

  implicit val GetResultUuid: GetResult[UUID] = slick.jdbc.GetResult { rs =>
    val bts = java.nio.ByteBuffer.wrap(rs.nextBytes())
    new java.util.UUID(bts.getLong(), bts.getLong())
  }

  implicit val GetResultDef: GetResult[Definition] = slick.jdbc.GetResult { rs =>
    implicitly[GeneratedMessageCompanion[Definition]].parseFrom(rs.nextBytes())
  }

  implicit def pbMapper[T <: GeneratedMessage: ClassTag](implicit
    companion: GeneratedMessageCompanion[T]
  ): BaseColumnType[T] =
    MappedColumnType.base((pb: T) => pb.toByteArray, (bts: Array[Byte]) => companion.parseFrom(bts))

  implicit val ec: ExecutionContext = ExecutionContext.parasitic

  class DefinitionIndexView(tag: Tag) extends Table[DefinitionIndexViewRow](tag, "definition_index_view") {

    // for debug only
    def name: Rep[String] = column[String]("NAME", O.Length(400))

    def definition: Rep[Definition] = column[Definition]("DEFINITION")

    def ownerId: Rep[UUID] = column[UUID]("OWNER_ID")

    // coordinates/location inside akka-sharding
    def bucketId: Rep[Long] = column[Long]("BUCKET_ID")

    def sequenceNr: Rep[Long] = column[Long]("SEQ_NUM")
    // coordinates

    def isLocked: Rep[Boolean] = column[Boolean]("IS_LOCKED")

    def lockTs: Rep[Long] = column[Long]("LOCK_TS")

    def pk: slick.lifted.PrimaryKey = primaryKey("DIV__PK", (bucketId, sequenceNr))

    def ownerIdIndex: slick.lifted.Index = index("DEF_IND_VIEW__OWNER_ID_IND", ownerId, unique = true)

    def * : slick.lifted.ProvenShape[DefinitionIndexViewRow] =
      (name, definition, ownerId, bucketId, sequenceNr, isLocked, lockTs) <>
        ((DefinitionIndexViewRow.apply _).tupled, DefinitionIndexViewRow.unapply)
  }

  object definitionIndexView extends TableQuery(new DefinitionIndexView(_)) {
    self =>

    def readNoWaitLocationDefinition(ownerId: UUID): DBIO[Option[(Long, Long, Definition, Boolean, Long)]] = {
      // Default(waits) | SKIP LOCKED(skip locked rows) |  NOWAIT(fails fast)
      /*definitionIndexView
        .filter(_.ownerId === ownerId)
        .map(rep => (rep.bucketId, rep.sequenceNr, rep.definition, rep.isLocked, rep.lockTs))
        .forUpdate
        .result
        .headOption*/

      // sql"""SELECT BUCKET_ID, SEQ_NUM, DEFINITION FROM definition_index_view WHERE OWNER_ID = UUID_TO_BIN('#$ownerId') FOR UPDATE SKIP LOCKED"""

      val readNoWait =
        sql"""SELECT BUCKET_ID, SEQ_NUM, DEFINITION, IS_LOCKED, LOCK_TS FROM definition_index_view WHERE OWNER_ID = UUID_TO_BIN('#$ownerId') AND IS_LOCKED=false FOR UPDATE NOWAIT"""
          .as[(Long, Long, Definition, Boolean, Long)]
          .headOption
      readNoWait
    }

    val locationDefinition = Compiled { (ownerId: Rep[UUID]) =>
      self
        .filter(_.ownerId === ownerId)
        .map(rep => (rep.bucketId, rep.sequenceNr, rep.definition))
    }

    def getLocationDefinition(ownerId: UUID): Future[Option[(Long, Long, Definition)]] =
      db.run(locationDefinition(ownerId).result.headOption)

    // https://github.com/haghard/unique-definition-management/blob/main/src/main/scala/com/definition/Guardian.scala
    def createAndUnlock(row: DefinitionIndexViewRow): Future[Done] = {
      val insertNew = definitionIndexView.insertOrUpdate(row)
      val unlock    = temporalConstraints.filter(_.ownerId === row.ownerId).delete
      val dbio      = (insertNew >> unlock).transactionally
      db.run(dbio).map(_ => Done)
    }

    def updateAndUnlock(row: DefinitionIndexViewRow): Future[Done] = {
      val update =
        definitionIndexView
          .filter(_.ownerId === row.ownerId)
          .map(rep => (rep.bucketId, rep.sequenceNr, rep.definition, rep.name, rep.lockTs, rep.isLocked))
          .update((row.bucketId, row.sequenceNr, row.definition, row.name, row.when, false))

      db.run(update).map(_ => Done)
    }
  }

  class TemporalConstraints(tag: Tag) extends Table[TemporalConstraintRow](tag, "temporal_constraints") {

    def ownerId: Rep[UUID] = column[UUID]("OWNER_ID")

    def when: Rep[Long] = column[Long]("WHEN")

    def pk: slick.lifted.PrimaryKey = primaryKey("TC_OWNER_ID__PK", ownerId)

    def * : slick.lifted.ProvenShape[TemporalConstraintRow] =
      (ownerId, when) <>
        ((TemporalConstraintRow.apply _).tupled, TemporalConstraintRow.unapply)
  }

  // for Create operation only
  object temporalConstraints extends TableQuery(new TemporalConstraints(_)) {
    self =>

    def acquireCreateLock(
      ownerId: UUID,
      lockTtl: Long
    ): DBIO[CreateResult] = {
      val now    = System.currentTimeMillis()
      val select = self.filter(_.ownerId === ownerId)
      select.forUpdate.result.headOption
        .flatMap {
          case None =>
            self
              .+=(TemporalConstraintRow(ownerId, now))
              .map(insertResult => if (insertResult == 1) CreateResult.Locked else CreateResult.Conflict)

          case Some(row) =>
            val nextTimeSlot = now - lockTtl
            if (row.when < nextTimeSlot) {
              select
                .map(_.when)
                .update(now)
                .map(insertResult => if (insertResult == 1) CreateResult.Locked else CreateResult.Conflict)
            } else {
              DBIO.successful(CreateResult.Conflict)
            }
        }
        .asTry
        .map(
          _.fold(
            { ex =>
              ex.printStackTrace()
              CreateResult.Conflict
            },
            identity
          )
        )
        .transactionally
    }
  }

  sealed trait CreateResult

  object CreateResult {
    case object Locked extends CreateResult

    case object Conflict extends CreateResult

    case class Ok(definitionLocation: DefinitionLocation) extends CreateResult

    case class AnotherDefinitionFound(entityId: Long, seqNum: Long) extends CreateResult

    case object NRowsFound extends CreateResult
  }

  def create(
    in: PutRequest,
    lockTTL: Long
  )(ask: PutRequest => Future[PutReply])(implicit ec: ExecutionContext): Future[PutReply] = {
    val ownerId = UUID.fromString(in.ownerId)
    val dbio    =
      definitionIndexView
        .locationDefinition(ownerId)
        .result
        .headOption
        .flatMap {
          case None =>
            temporalConstraints.acquireCreateLock(ownerId, lockTTL)
          case Some(row) =>
            val (entityId, seqNum, definition) = row
            if (definition == in.definition) {
              DBIO.successful(CreateResult.Ok(DefinitionLocation(entityId, seqNum)))
            } else {
              DBIO.successful(CreateResult.AnotherDefinitionFound(entityId, seqNum))
            }
          case _ =>
            DBIO.successful(CreateResult.NRowsFound)
        }
        .transactionally

    db.run(dbio)
      .flatMap {
        case CreateResult.Locked =>
          ask(in)
        case CreateResult.Conflict =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.CreateConflict,
              DefinitionLocation(-1, -1)
            )
          )
        case CreateResult.Ok(definitionLocation) =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.OKNoOp,
              definitionLocation
            )
          )
        case CreateResult.AnotherDefinitionFound(entityId, seqNum) =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.AnotherDefinitionFound,
              DefinitionLocation(entityId, seqNum)
            )
          )
        case CreateResult.NRowsFound =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.IllegalState,
              DefinitionLocation(-1, -1)
            )
          )
      }(ec)
  }

  sealed trait UpdateResult

  object UpdateResult {
    case class Locked(definitionLocation: DefinitionLocation) extends UpdateResult

    case object Conflict extends UpdateResult

    case object IllegalState extends UpdateResult

    case class OK(definitionLocation: DefinitionLocation) extends UpdateResult

    case object LocationNotFound extends UpdateResult

    case object ReadNoWaitNotFound extends UpdateResult
  }

  def update(
    in: PutRequest,
    lockTtl: Long
  )(ask: (PutRequest, DefinitionLocation) => Future[PutReply])(implicit ec: ExecutionContext): Future[PutReply] = {
    val ownerId                  = UUID.fromString(in.ownerId)
    val dbio: DBIO[UpdateResult] =
      definitionIndexView
        .readNoWaitLocationDefinition(ownerId)
        .flatMap {
          case None =>
            DBIO.successful(UpdateResult.ReadNoWaitNotFound)

          case Some(row) =>
            val (bucketId, seqNum, definition, isLocked, lockedAt) = row
            val currentLocation                                    = DefinitionLocation(bucketId, seqNum)
            if (in.getLocation == currentLocation) {
              if (in.definition == definition) {
                DBIO.successful(UpdateResult.OK(currentLocation))
              } else {
                val now    = System.currentTimeMillis()
                val update = definitionIndexView
                  .filter(_.ownerId === ownerId)
                  .map(rep => (rep.isLocked, rep.lockTs))
                  .update((true, now))
                  .map(_ => UpdateResult.Locked(currentLocation))
                if (isLocked) {
                  if ((now - lockedAt) > lockTtl) update else DBIO.successful(UpdateResult.Conflict)
                } else {
                  update
                }
              }
            } else {
              DBIO.successful(UpdateResult.LocationNotFound)
            }
        }
        .asTry
        .map(_.fold({ e => e.printStackTrace(); UpdateResult.Conflict }, identity))
        .transactionally

    val dbOp: Future[UpdateResult] = db.run(dbio)
    dbOp
      .flatMap {
        case UpdateResult.Locked(location) =>
          ask(in, location)

        case UpdateResult.ReadNoWaitNotFound =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.ReadNoWaitNotFound,
              DefinitionLocation(-1, -1)
            )
          )

        case UpdateResult.Conflict =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.UpdateConflict,
              DefinitionLocation(-1, -1)
            )
          )

        case UpdateResult.OK(location) =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.OKNoOp,
              location
            )
          )

        case UpdateResult.LocationNotFound =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.LocationNotFound,
              DefinitionLocation(-1, -1)
            )
          )

        case UpdateResult.IllegalState =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.IllegalState,
              DefinitionLocation(-1, -1)
            )
          )
      }(ec)
  }

  val tables           = Seq(definitionIndexView, temporalConstraints)
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
      .flatMap(_ => SlickProjection.createTablesIfNotExists(dbConfig))
}

object Tables extends SlickTablesGeneric(slick.jdbc.MySQLProfile)

/*
akka.pattern.retry(
    attempt = () => mkF(ownerId, definition),
    attempts = 8,
    delayFunction = { i => Option(75.millis) }
)(system.executionContext, system.scheduler.toClassic)
 */
