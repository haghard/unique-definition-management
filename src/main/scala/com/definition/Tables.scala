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
import scala.util.{Failure, Success, Using}

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

  /*val GetResultDef: GetResult[(Long, Long, Definition)] = slick.jdbc.GetResult { rs =>
    (rs.nextLong(), rs.nextLong(), implicitly[GeneratedMessageCompanion[Definition]].parseFrom(rs.nextBytes()))
  }*/
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

    def when: Rep[Long] = column[Long]("WHEN")

    def pk: slick.lifted.PrimaryKey = primaryKey("DIV__PK", (bucketId, sequenceNr))

    def ownerIdIndex: slick.lifted.Index = index("DEF_IND_VIEW__OWNER_ID_IND", ownerId, unique = true)

    def * : slick.lifted.ProvenShape[DefinitionIndexViewRow] =
      (name, definition, ownerId, bucketId, sequenceNr, when) <>
        ((DefinitionIndexViewRow.apply _).tupled, DefinitionIndexViewRow.unapply)
  }

  object definitionIndexView extends TableQuery(new DefinitionIndexView(_)) {
    self =>

    def getAndLock(ownerId: UUID): DBIO[Seq[(Long, Long, Definition)]] = {
      // https://dev.mysql.com/doc/refman/8.4/en/innodb-transaction-isolation-levels.html

      // Default(waits) | SKIP LOCKED(skip locked rows) |  NOWAIT(fails fast)
      /*val q =
        definitionIndexView
          .filter(_.ownerId === ownerId)
          .map(rep => (rep.bucketId, rep.sequenceNr, rep.definition))
          .forUpdate
          .result*/

      val q =
        sql"""SELECT BUCKET_ID, SEQ_NUM, DEFINITION FROM definition_index_view WHERE OWNER_ID = UUID_TO_BIN('#$ownerId') FOR UPDATE NOWAIT"""
          // sql"""SELECT BUCKET_ID, SEQ_NUM, DEFINITION FROM definition_index_view WHERE OWNER_ID = UUID_TO_BIN('#$ownerId') FOR UPDATE SKIP LOCKED"""
          .as[(Long, Long, Definition)]
      q.statements.foreach(println)
      q
    }

    val locationDefinition = Compiled { (ownerId: Rep[UUID]) =>
      self.filter(_.ownerId === ownerId).map(rep => (rep.bucketId, rep.sequenceNr, rep.definition))
    }

    def getLocationDefinition(ownerId: UUID): Future[scala.collection.immutable.Seq[(Long, Long, Definition)]] =
      db.run(locationDefinition(ownerId).result)

    // https://github.com/haghard/unique-definition-management/blob/main/src/main/scala/com/definition/Guardian.scala
    def createAndUnlock(row: DefinitionIndexViewRow): Future[Done] = {
      val insertNew = definitionIndexView.insertOrUpdate(row)
      val unlock    = temporalConstraints.filter(_.ownerId === row.ownerId).delete
      val dbio      = (insertNew >> unlock).transactionally
      db.run(dbio).map(_ => Done)
    }

    def updateAndUnlock(row: DefinitionIndexViewRow): Future[Done] = {
      val unlock = temporalConstraints.filter(_.ownerId === row.ownerId).delete
      val update =
        definitionIndexView
          .filter(_.ownerId === row.ownerId)
          .map(rep => (rep.bucketId, rep.sequenceNr, rep.definition, rep.name, rep.when))
          .update((row.bucketId, row.sequenceNr, row.definition, row.name, row.when))

      val dbio = (update >> unlock).transactionally
      db.run(dbio).map(_ => Done)
    }
  }

  final case class TemporalConstraintRow(ownerId: UUID, when: Long)

  class TemporalConstraints(tag: Tag) extends Table[TemporalConstraintRow](tag, "temporal_constraints") {

    def ownerId: Rep[UUID] = column[UUID]("OWNER_ID")

    def when: Rep[Long] = column[Long]("WHEN")

    def pk: slick.lifted.PrimaryKey = primaryKey("tc_OWNER_ID__PK", ownerId)

    def * : slick.lifted.ProvenShape[TemporalConstraintRow] =
      (ownerId, when) <>
        ((TemporalConstraintRow.apply _).tupled, TemporalConstraintRow.unapply)
  }

  object temporalConstraints extends TableQuery(new TemporalConstraints(_))

  sealed trait CreateResult

  object CreateResult {
    case object Locked extends CreateResult

    case object Conflict extends CreateResult

    case class IllegalState(entityId: Long, seqNum: Long) extends CreateResult

    case class AnotherDefinitionFound(entityId: Long, seqNum: Long) extends CreateResult

    case object NRowsFound extends CreateResult
  }

  def lockFreeCreate(
    in: PutRequest
  )(ask: PutRequest => Future[PutReply])(implicit ec: ExecutionContext): Future[PutReply] = {
    val ownerId = UUID.fromString(in.ownerId)

    val dbio =
      definitionIndexView
        .locationDefinition(ownerId)
        .result
        .flatMap { rows =>
          rows.size match {
            case 0 =>
              (temporalConstraints += TemporalConstraintRow(ownerId, System.nanoTime())).asTry.map {
                case Success(_) =>
                  CreateResult.Locked
                case Failure(_) =>
                  CreateResult.Conflict
              }
            case 1 =>
              val (entityId, seqNum, definition) = rows.head
              if (definition == in.definition) {
                DBIO.successful(CreateResult.IllegalState(entityId, seqNum))
              } else {
                DBIO.successful(CreateResult.AnotherDefinitionFound(entityId, seqNum))
              }
            case _ =>
              DBIO.successful(CreateResult.NRowsFound)
          }
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
        case CreateResult.IllegalState(entityId, seqNum) =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.IllegalState,
              DefinitionLocation(entityId, seqNum)
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

    case object NotFound extends UpdateResult
  }

  def lockFreeUpdate(
    in: PutRequest
  )(ask: (PutRequest, DefinitionLocation) => Future[PutReply])(implicit ec: ExecutionContext): Future[PutReply] = {
    val ownerId = UUID.fromString(in.ownerId)

    val dbio =
      definitionIndexView
        .locationDefinition(ownerId)
        .result
        .flatMap { rows =>
          rows.size match {
            case 0 =>
              DBIO.successful(UpdateResult.NotFound)
            case 1 =>
              // Use case: Same entity_id is used to create different definitions
              val (bucketId, seqNum, definition) = rows.head
              if (in.definition != definition) {
                if (bucketId == in.getDefinitionLocation.bucketId && seqNum == in.getDefinitionLocation.seqNum) {
                  (temporalConstraints += TemporalConstraintRow(ownerId, System.nanoTime())).asTry.map {
                    case Success(_) =>
                      UpdateResult.Locked(DefinitionLocation(bucketId, seqNum))
                    case Failure(_) =>
                      UpdateResult.Conflict
                  }
                } else {
                  DBIO.successful(UpdateResult.LocationNotFound)
                }
              } else {
                DBIO.successful(UpdateResult.OK(DefinitionLocation(bucketId, seqNum)))
              }
            case _ =>
              DBIO.successful(UpdateResult.IllegalState)
          }
        }
        .transactionally

    db.run(dbio)
      .flatMap {
        case UpdateResult.Locked(prevDefinitionLocation) =>
          ask(in, prevDefinitionLocation)
        case UpdateResult.Conflict =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.UpdateConflict,
              DefinitionLocation(-1, -1)
            )
          )
        case UpdateResult.OK(definitionLocation) =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.OK2,
              definitionLocation
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
        case UpdateResult.NotFound =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.NotFound,
              DefinitionLocation(-1, -1)
            )
          )
      }(ec)
  }

  //
  def withUpdateLock(
    in: PutRequest
  )(ask: (PutRequest, DefinitionLocation) => Future[PutReply])(implicit ec: ExecutionContext): Future[PutReply] = {
    val ownerId = UUID.fromString(in.ownerId)
    val dbio    =
      definitionIndexView
        .getAndLock(ownerId)
        .flatMap { rows =>
          rows.size match {
            case 0 =>
              DBIO.successful(
                PutReply(
                  in.ownerId,
                  PutReply.StatusCode.LocationNotFound,
                  DefinitionLocation(-1, -1)
                )
              )
            case 1 =>
              val (bucketId, seqNum, definition) = rows.head
              if (in.definition != definition) {
                if (bucketId == in.getDefinitionLocation.bucketId && seqNum == in.getDefinitionLocation.seqNum) {
                  // Keeps current trn open until ask completes
                  DBIO.from(ask(in, DefinitionLocation(bucketId, seqNum)))
                } else {
                  DBIO.successful(
                    PutReply(in.ownerId, PutReply.StatusCode.LocationNotFound, DefinitionLocation(-1, -1))
                  )
                }
              } else {
                DBIO.successful(
                  PutReply(
                    in.ownerId,
                    PutReply.StatusCode.OK2,
                    DefinitionLocation(bucketId, seqNum)
                  )
                )
              }
            case _ =>
              DBIO.successful(
                PutReply(
                  in.ownerId,
                  PutReply.StatusCode.IllegalState,
                  DefinitionLocation(-1, -1)
                )
              )
          }
        }
        .transactionally
        .asTry
        .map(
          _.fold(err => PutReply(in.ownerId, PutReply.StatusCode.UpdateConflict, DefinitionLocation(-1, -1)), identity)
        )

    db.run(dbio)
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
