/*
package com.definition

import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.projection.slick.SlickProjection
import com.definition.api.*
import com.definition.domain.*
import scalapb.*
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

sealed trait CreateResult

object CreateResult {
  final case object Locked extends CreateResult

  final case object Conflict extends CreateResult

  final case class Ok(definitionLocation: DefinitionLocation) extends CreateResult

  final case class AnotherDefinitionFound(entityId: Long, seqNum: Long) extends CreateResult

  final case object NRowsFound extends CreateResult
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

    def bucketId: Rep[Long] = column[Long]("BUCKET_ID")

    def sequenceNr: Rep[Long] = column[Long]("SEQ_NUM")

    def isLocked: Rep[Boolean] = column[Boolean]("IS_LOCKED")

    def when: Rep[Long] = column[Long]("TS")

    def pk: slick.lifted.PrimaryKey = primaryKey("DIV__PK", (bucketId, sequenceNr))

    def ownerIdIndex: slick.lifted.Index = index("DEF_IND_VIEW__OWNER_ID_IND", ownerId, unique = true)

    def * : slick.lifted.ProvenShape[DefinitionIndexViewRow] =
      (name, definition, ownerId, bucketId, sequenceNr, isLocked, when) <>
        ((DefinitionIndexViewRow.apply _).tupled, DefinitionIndexViewRow.unapply)
  }

  object definitionIndexView extends TableQuery(new DefinitionIndexView(_)) { self =>
    def readAndLockDefinition(
      ownerId: UUID
    ): DBIO[scala.collection.immutable.Seq[(Long, Long, Definition, Boolean, Long)]] = {
      println("sdfasd")

      self
        .filter(_.ownerId === ownerId)
        .map(rep => (rep.bucketId, rep.sequenceNr, rep.definition, rep.isLocked, rep.when))
        .forUpdate
        .result

      /*sql"""SELECT BUCKET_ID, SEQ_NUM, DEFINITION, IS_LOCKED, TS FROM definition_index_view WHERE OWNER_ID = UUID_TO_BIN('#$ownerId') FOR UPDATE NOWAIT"""
        .as[(Long, Long, Definition, Boolean, Long)]*/

      // fail fast
      // sql"""SELECT BUCKET_ID, SEQ_NUM, DEFINITION, IS_LOCKED, TS FROM definition_index_view WHERE OWNER_ID = UUID_TO_BIN('#$ownerId') FOR UPDATE"""    //wait
      // sql"""SELECT BUCKET_ID, SEQ_NUM, DEFINITION, IS_LOCKED, TS FROM definition_index_view WHERE OWNER_ID = UUID_TO_BIN('#$ownerId') FOR UPDATE SKIP LOCKED""" //skip locked rows and return fast

    }

    val locationDefinition = Compiled { (ownerId: Rep[UUID]) =>
      self.filter(_.ownerId === ownerId).map(rep => (rep.bucketId, rep.sequenceNr, rep.definition))
    }

    def getCurrentLocation(ownerId: UUID): Future[scala.collection.immutable.Seq[(Long, Long, Definition)]] = {
      println("sdfasd")
      /*db.run(
        sql"""SELECT BUCKET_ID, SEQ_NUM, DEFINITION FROM definition_index_view WHERE OWNER_ID = UUID_TO_BIN('#$ownerId') FOR SHARE"""
          .as[(Long, Long, Definition)]
      )*/
      db.run(self.locationDefinition(ownerId).result)
    }

    def createAndUnlock(row: DefinitionIndexViewRow): Future[Done] = {
      val insertNew = definitionIndexView.insertOrUpdate(row)
      val unlock    = temporalConstraints.filter(_.ownerId === row.ownerId).delete
      val dbio      = (insertNew >> unlock).transactionally
      db.run(dbio).map(_ => Done)
    }

    def updateAndUnlock(row: DefinitionIndexViewRow): Future[Done] = {
      val select = definitionIndexView
        .filter(_.ownerId === row.ownerId)
        .map(rep => (rep.bucketId, rep.sequenceNr, rep.definition, rep.name, rep.when, rep.isLocked))

      val update =
        select.forUpdate.result.headOption.flatMap {
          case Some(cur) =>
            val startTs = cur._5
            select
              .update((row.bucketId, row.sequenceNr, row.definition, row.name, row.when, false))
              .map(_ => System.currentTimeMillis() - startTs)
          case None =>
            DBIO.successful(0)
        }.transactionally

      db.run(update).map { latency => println(latency); Done }
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
              .map(rc => if (rc == 1) CreateResult.Locked else CreateResult.Conflict)
          case Some(row) =>
            val nextTs = now - lockTtl
            if (row.when < nextTs) {
              select.map(_.when).update(now).map(rc => if (rc == 1) CreateResult.Locked else CreateResult.Conflict)
            } else {
              DBIO.successful(CreateResult.Conflict)
            }
        }
        .asTry
        .map(_.fold(_ => CreateResult.Conflict, identity))
        .transactionally
    }
  }

  def create(
    in: PutRequest,
    lockTtl: Long
  )(ask: PutRequest => Future[PutReply])(implicit ec: ExecutionContext): Future[PutReply] = {
    val ownerId                  = UUID.fromString(in.ownerId)
    val dbio: DBIO[CreateResult] =
      definitionIndexView
        .locationDefinition(ownerId)
        .result
        .flatMap { rows =>
          rows.size match {
            case 0 =>
              temporalConstraints.acquireCreateLock(ownerId, lockTtl)
            case 1 =>
              val (entityId, seqNum, definition) = rows.head
              if (definition == in.definition) {
                DBIO.successful(CreateResult.Ok(DefinitionLocation(entityId, seqNum)))
              } else {
                DBIO.successful(CreateResult.AnotherDefinitionFound(entityId, seqNum))
              }
            case _ =>
              DBIO.successful(CreateResult.NRowsFound)
          }
        }

    val dbOp: Future[CreateResult] = db.run(dbio)
    dbOp
      .flatMap {
        case CreateResult.Locked =>
          // if (ThreadLocalRandom.current().nextDouble() > .4) ask(in) else Future.failed(new Exception("Boom !!!"))
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
              PutReply.StatusCode.OK2,
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

  def update(
    in: PutRequest,
    lockTtl: Long
  )(ask: (PutRequest, DefinitionLocation) => Future[PutReply])(implicit ec: ExecutionContext): Future[PutReply] = {
    val ownerId = UUID.fromString(in.ownerId)
    val dbio    =
      definitionIndexView
        .readAndLockDefinition(ownerId)
        .flatMap { rows =>
          rows.size match {
            case 0 =>
              DBIO.successful(UpdateResult.LocationNotFound)
            case 1 =>
              val (bucketId, seqNum, definition, isLocked, lockedAt) = rows.head
              if (in.definition != definition) {
                if (bucketId == in.getLocation.bucketId && seqNum == in.getLocation.seqNum) {

                  val now    = System.currentTimeMillis()
                  val update = definitionIndexView
                    .filter(_.ownerId === ownerId)
                    .map(rep => (rep.isLocked, rep.when))
                    .update((true, now))
                    .map(_ => UpdateResult.Locked(DefinitionLocation(bucketId, seqNum)))

                  if (isLocked) {
                    if ((now - lockedAt) > lockTtl) update else DBIO.successful(UpdateResult.Conflict)
                  } else {
                    update
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
        .asTry
        .map(_.fold({ e => e.printStackTrace(); UpdateResult.Conflict }, identity))
        .transactionally

    val dbOp: Future[UpdateResult] = db.run(dbio)

    dbOp
      .flatMap {
        case UpdateResult.Locked(prevDefinitionLocation) =>
          // if (ThreadLocalRandom.current().nextDouble() > .4) ask(in, prevDefinitionLocation) else Future.failed(new Exception("Boom !!!"))
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

  val tables           = Seq(definitionIndexView, temporalConstraints)
  val ddl: profile.DDL = tables.map(_.schema).reduce(_ ++ _)

  val dbConfig = DatabaseConfig.forConfig[MySQLProfile]("pekko.projection.slick")

  val db = {
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

object RelationalData extends SlickTablesGeneric(slick.jdbc.MySQLProfile)
 */

/*
akka.pattern.retry(
    attempt = () => mkF(ownerId, definition),
    attempts = 8,
    delayFunction = { i => Option(75.millis) }
)(system.executionContext, system.scheduler.toClassic)
 */
