package com.definition

import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.projection.slick.SlickProjection
import com.definition.api.*
import com.definition.domain.*
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils
import scalapb.*
import slick.basic.DatabaseConfig
import slick.jdbc.{GetResult, MySQLProfile, PostgresProfile}

import java.util.UUID
import scala.concurrent.*
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Using}
import scala.util.control.NonFatal

final case class DefinitionIndexViewRow(
  shardId: Int,
  definitionId: Long,
  ownerId: UUID,
  isLocked: Boolean = false,
  when: Long
)

final case class TemporalConstraintRow(ownerId: UUID, when: Long)

sealed trait CreateResult

object CreateResult {
  final case class Locked(location: DefinitionLocation) extends CreateResult

  final case object Conflict extends CreateResult

  final case class Ok(location: DefinitionLocation) extends CreateResult

  final case class AnotherDefinitionFound(location: DefinitionLocation) extends CreateResult

  final case class MappingNotFound(location: DefinitionLocation) extends CreateResult

  final case object NRowsFound extends CreateResult
}

sealed trait UpdateResult

object UpdateResult {
  case class Locked(prevLocation: DefinitionLocation, newLocation: DefinitionLocation) extends UpdateResult

  case object Conflict extends UpdateResult

  case object IllegalState extends UpdateResult

  case class OK(definitionLocation: DefinitionLocation) extends UpdateResult

  case object WrongLocation extends UpdateResult

  case object NotFound extends UpdateResult
}

final case class MappingRow(id: Option[Long], definition: Definition)

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
    def shardId: Rep[Int]       = column("SHARD_ID")
    def definitionId: Rep[Long] = column("DEFINITION_ID")
    def ownerId: Rep[UUID]      = column[UUID]("OWNER_ID")
    def isLocked: Rep[Boolean]  = column[Boolean]("IS_LOCKED")
    def lockTs: Rep[Long]       = column[Long]("LOCK_TS")

    def pk: slick.lifted.PrimaryKey      = primaryKey("DIV__PK_ID", (shardId, definitionId))
    def ownerIdIndex: slick.lifted.Index = index("DEF_IND_VIEW__OWNER_ID_IND", ownerId, unique = true)

    def * : slick.lifted.ProvenShape[DefinitionIndexViewRow] =
      (shardId, definitionId, ownerId, isLocked, lockTs) <>
        ((DefinitionIndexViewRow.apply _).tupled, DefinitionIndexViewRow.unapply)
  }

  object definitionIndexView extends TableQuery(new DefinitionIndexView(_)) {
    self =>

    def readAndLockDefinition(
      ownerId: UUID
    ): DBIO[Option[(Int, Long, Boolean, Long)]] = {
      self
        .filter(_.ownerId === ownerId)
        .map(rep => (rep.shardId, rep.definitionId, rep.isLocked, rep.lockTs))
        .forUpdate
        .result
        .headOption

      /*sql"""SELECT SHARD_ID, DEFINITION_ID, IS_LOCKED, TS FROM definition_index_view WHERE OWNER_ID = UUID_TO_BIN('#$ownerId') FOR UPDATE NOWAIT"""
        .as[(Int, Long, Boolean, Long)]*/

      // fail fast
      // sql"""SELECT BUCKET_ID, SEQ_NUM, DEFINITION, IS_LOCKED, TS FROM definition_index_view WHERE OWNER_ID = UUID_TO_BIN('#$ownerId') FOR UPDATE"""    //wait
      // sql"""SELECT BUCKET_ID, SEQ_NUM, DEFINITION, IS_LOCKED, TS FROM definition_index_view WHERE OWNER_ID = UUID_TO_BIN('#$ownerId') FOR UPDATE SKIP LOCKED""" //skip locked rows and return fast

    }

    def locationDefinition(ownerId: Rep[UUID]) =
      self.filter(_.ownerId === ownerId).map(rep => (rep.shardId, rep.definitionId))

    def getCurrentLocation(ownerId: UUID): Future[Option[(Int, Long)]] =
      /*db.run(
        sql"""SELECT SHARD_ID, DEFINITION_ID FROM definition_index_view WHERE OWNER_ID = UUID_TO_BIN('#$ownerId') FOR SHARE"""
          .as[(Int, Long)]
      )*/
      db.run(locationDefinition(ownerId).result.headOption)

    def createAndUnlock(row: DefinitionIndexViewRow): Future[Done] = {
      val insertNew = definitionIndexView.insertOrUpdate(row)
      val unlock    = temporalConstraints.filter(_.ownerId === row.ownerId).delete
      val dbio      = (insertNew >> unlock).transactionally
      db.run(dbio).map(_ => Done)
    }

    def updateAndUnlock(row: DefinitionIndexViewRow /*prev: DefinitionLocation*/ ): Future[Done] = {
      val select = definitionIndexView
        .filter(_.ownerId === row.ownerId)
        .map(rep => (rep.shardId, rep.definitionId, rep.lockTs, rep.isLocked))

      val update =
        select.forUpdate.result.headOption.flatMap {
          case Some(curRow) =>
            /*val deletePrev =
              (prev.shardId match {
                case 0 => mapping0.filter(_.definitionId === prev.definitionId)
                case 1 => mapping1.filter(_.definitionId === prev.definitionId)
                case 2 => mapping2.filter(_.definitionId === prev.definitionId)
                case 3 => mapping3.filter(_.definitionId === prev.definitionId)
                case n => throw new Exception(s"mapping$n doesn't exist")
              }).delete*/

            val startTs = curRow._2
            select
              .update((row.shardId, row.definitionId, row.when, false))
              .map(_ => System.currentTimeMillis() - startTs) /*>> deletePrev*/
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

  object temporalConstraints extends TableQuery(new TemporalConstraints(_)) {
    self =>

    def acquireCreateLock(
      ownerId: UUID,
      lockTtl: Long,
      definition: Definition,
      mappingTableId: Int
    ): DBIO[CreateResult] = {
      val now    = System.currentTimeMillis()
      val select = self.filter(_.ownerId === ownerId)
      select.forUpdate.result.headOption
        .flatMap {
          case None =>
            self
              .+=(TemporalConstraintRow(ownerId, now))
              .flatMap { rc =>
                if (rc == 1) {
                  getOrInsertMapping(mappingTableId, definition)
                    .map(definitionId => CreateResult.Locked(DefinitionLocation(mappingTableId, definitionId)))
                } else {
                  DBIO.successful(CreateResult.Conflict)
                }
              }
          case Some(row) =>
            val nextTimeSlot = now - lockTtl
            if (row.when < nextTimeSlot) {
              select
                .map(_.when)
                .update(now)
                .flatMap { rc =>
                  if (rc == 1) {
                    getOrInsertMapping(mappingTableId, definition)
                      .map(definitionId => CreateResult.Locked(DefinitionLocation(mappingTableId, definitionId)))
                  } else {
                    DBIO.successful(CreateResult.Conflict)
                  }
                }
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

  private def getOrInsertMapping(mappingTableId: Int, definition: Definition) =
    mappingTableId match {
      case 0 => mapping0.getOrInsert(definition)
      case 1 => mapping1.getOrInsert(definition)
      case 2 => mapping2.getOrInsert(definition)
      case 3 => mapping3.getOrInsert(definition)
      case n => throw new Exception(s"mapping$n doesn't exist")
    }

  class MappingTable0(tag: Tag) extends Table[MappingRow](tag, "mapping0") {
    def definitionId: Rep[Long]     = column[Long]("DEFINITION_ID", O.PrimaryKey, O.AutoInc)
    def definition: Rep[Definition] = column[Definition]("DEFINITION")

    def * : slick.lifted.ProvenShape[MappingRow] =
      (definitionId.?, definition) <> ((MappingRow.apply _).tupled, MappingRow.unapply)
  }

  class MappingTable1(tag: Tag) extends Table[MappingRow](tag, "mapping1") {
    def definitionId: Rep[Long]     = column[Long]("DEFINITION_ID", O.PrimaryKey, O.AutoInc)
    def definition: Rep[Definition] = column[Definition]("DEFINITION")

    def * : slick.lifted.ProvenShape[MappingRow] =
      (definitionId.?, definition) <> ((MappingRow.apply _).tupled, MappingRow.unapply)
  }

  class MappingTable2(tag: Tag) extends Table[MappingRow](tag, "mapping2") {
    def definitionId: Rep[Long]     = column[Long]("DEFINITION_ID", O.PrimaryKey, O.AutoInc)
    def definition: Rep[Definition] = column[Definition]("DEFINITION")

    def * : slick.lifted.ProvenShape[MappingRow] =
      (definitionId.?, definition) <> ((MappingRow.apply _).tupled, MappingRow.unapply)
  }

  class MappingTable3(tag: Tag) extends Table[MappingRow](tag, "mapping3") {
    def definitionId: Rep[Long]     = column[Long]("DEFINITION_ID", O.PrimaryKey, O.AutoInc)
    def definition: Rep[Definition] = column[Definition]("DEFINITION")

    def * : slick.lifted.ProvenShape[MappingRow] =
      (definitionId.?, definition) <> ((MappingRow.apply _).tupled, MappingRow.unapply)
  }

  // N mapping tables (database partitioning - vertical database scalability by multiple tables in one physical database)
  object mapping0 extends TableQuery(new MappingTable0(_)) {
    self =>
    val selectId = self.map(_.definitionId)

    def getDefinitionById(definitionId: Long): DBIO[Option[Definition]] =
      self.filter(_.definitionId === definitionId).map(_.definition).result.headOption

    // TODO
    def getOrInsert2(definition: Definition): DBIO[Long] =
      // burns definitionId, maybe try a trigger or ON CONFLICT RETURN ???
      self
        .returning(selectId)
        .+=(MappingRow(None, definition))
        .asTry
        .flatMap {
          case Success(id) =>
            DBIO.successful(id)
          case Failure(ex) =>
            ex.printStackTrace()
            self
              .filter(_.definition === definition)
              .map(_.definitionId)
              .result
              .headOption
              .map(_.getOrElse(-1))
        }

    def getOrInsert(definition: Definition): DBIO[Long] =
      self
        .filter(_.definition === definition)
        .map(_.definitionId)
        .result
        .headOption
        .flatMap {
          case Some(id) =>
            DBIO.successful(id)
          case None =>
            self
              .returning(selectId)
              .+=(MappingRow(None, definition))
        }
  }

  object mapping1 extends TableQuery(new MappingTable1(_)) {
    self =>
    val selectId = self.map(_.definitionId)

    def getDefinitionById(definitionId: Long): DBIO[Option[Definition]] =
      self.filter(_.definitionId === definitionId).map(_.definition).result.headOption

    def getOrInsert(definition: Definition): DBIO[Long] =
      self
        .filter(_.definition === definition)
        .map(_.definitionId)
        .result
        .headOption
        .flatMap {
          case Some(id) =>
            DBIO.successful(id)
          case None =>
            self
              .returning(selectId)
              .+=(MappingRow(None, definition))
        }
  }

  object mapping2 extends TableQuery(new MappingTable2(_)) {
    self =>
    val selectId = self.map(_.definitionId)

    def getDefinitionById(id: Long): DBIO[Option[Definition]] =
      self.filter(_.definitionId === id).map(_.definition).result.headOption

    def getOrInsert(definition: Definition): DBIO[Long] =
      self
        .filter(_.definition === definition)
        .map(_.definitionId)
        .result
        .headOption
        .flatMap {
          case Some(id) =>
            DBIO.successful(id)
          case None =>
            self
              .returning(selectId)
              .+=(MappingRow(None, definition))
        }
  }

  object mapping3 extends TableQuery(new MappingTable3(_)) {
    self =>
    val selectId = self.map(_.definitionId)

    def getDefinitionById(id: Long): DBIO[Option[Definition]] =
      self.filter(_.definitionId === id).map(_.definition).result.headOption

    def getOrInsert(definition: Definition): DBIO[Long] =
      self
        .filter(_.definition === definition)
        .map(_.definitionId)
        .result
        .headOption
        .flatMap {
          case Some(definitionId) =>
            DBIO.successful(definitionId)
          case None =>
            self
              .returning(selectId)
              .+=(MappingRow(None, definition))
        }
  }

  def create(
    in: PutRequest,
    lockTtl: Long,
    shardIdFromRequest: Int
  )(ask: (PutRequest, DefinitionLocation) => Future[PutReply])(implicit ec: ExecutionContext): Future[PutReply] = {
    val ownerId                  = UUID.fromString(in.ownerId)
    val dbio: DBIO[CreateResult] =
      definitionIndexView
        .locationDefinition(ownerId)
        .result
        .headOption
        .flatMap {
          case None =>
            temporalConstraints.acquireCreateLock(ownerId, lockTtl, in.definition, shardIdFromRequest)
          case Some(row) =>
            val currentLocation = DefinitionLocation(row._1, row._2)
            (currentLocation.shardId match {
              case 0 => mapping0.getDefinitionById(currentLocation.definitionId)
              case 1 => mapping1.getDefinitionById(currentLocation.definitionId)
              case 2 => mapping2.getDefinitionById(currentLocation.definitionId)
              case 3 => mapping3.getDefinitionById(currentLocation.definitionId)
              case n => throw new Exception(s"mapping$n doesn't exist")
            }).map {
              case Some(definition) =>
                if (definition == in.definition)
                  CreateResult.Ok(currentLocation)
                else
                  CreateResult.AnotherDefinitionFound(currentLocation)
              case None =>
                CreateResult.MappingNotFound(currentLocation)
            }
          case _ =>
            DBIO.successful(CreateResult.NRowsFound)
        }

    val dbOp: Future[CreateResult] = db.run(dbio)
    dbOp
      .flatMap {
        case CreateResult.Locked(definitionLocation) =>
          // if (ThreadLocalRandom.current().nextDouble() > .4) ask(in) else Future.failed(new Exception("Boom !!!"))
          ask(in, definitionLocation)

        case CreateResult.Conflict =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.CreateConflict,
              DefinitionLocation(-1, -1)
            )
          )
        case CreateResult.Ok(location) =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.OK2,
              location
            )
          )
        case CreateResult.AnotherDefinitionFound(location) =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.AnotherDefinitionFound,
              location
            )
          )

        case CreateResult.MappingNotFound(location) =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.IllegalState,
              location
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
    lockTtl: Long,
    newShardId: Int
  )(
    ask: (PutRequest, DefinitionLocation, DefinitionLocation) => Future[PutReply]
  )(implicit ec: ExecutionContext): Future[PutReply] = {
    val ownerId = UUID.fromString(in.ownerId)
    val dbio    =
      definitionIndexView
        .readAndLockDefinition(ownerId)
        .flatMap {
          case None =>
            DBIO.successful(UpdateResult.WrongLocation)
          case Some((shardId, definitionId, isLocked, lockedAt)) =>
            if (in.getLocation == DefinitionLocation(shardId, definitionId)) {
              getOrInsertMapping(newShardId, in.definition)
                .flatMap { newLocationId =>
                  val prevLocation = DefinitionLocation(shardId, definitionId)
                  val newLocation  = DefinitionLocation(newShardId, newLocationId)
                  if (prevLocation != newLocation) {
                    val now    = System.currentTimeMillis()
                    val update = definitionIndexView
                      .filter(_.ownerId === ownerId)
                      .map(rep => (rep.isLocked, rep.lockTs))
                      .update((true, now))
                      .map(_ => UpdateResult.Locked(prevLocation, newLocation))
                    if (isLocked) {
                      if ((now - lockedAt) > lockTtl) update else DBIO.successful(UpdateResult.Conflict)
                    } else {
                      update
                    }
                  } else {
                    DBIO.successful(UpdateResult.OK(newLocation))
                  }
                }
            } else {
              DBIO.successful(UpdateResult.WrongLocation)
            }
        }
        .asTry
        .map(_.fold({ e => e.printStackTrace(); UpdateResult.Conflict }, identity))
        .transactionally

    val dbOp: Future[UpdateResult] = db.run(dbio)

    dbOp
      .flatMap {
        case UpdateResult.Locked(prevLocation, newLocation) =>
          // if (ThreadLocalRandom.current().nextDouble() > .4) ask(in, prevDefinitionLocation) else Future.failed(new Exception("Boom !!!"))
          ask(in, prevLocation, newLocation)

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
              PutReply.StatusCode.OK2,
              location
            )
          )

        case UpdateResult.WrongLocation =>
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

  // pekko.cluster.sharding.number-of-shards = 4
  val mappingTables    = Vector(mapping0, mapping1, mapping2, mapping3)
  val tables           = Vector(definitionIndexView, temporalConstraints)
  val ddl: profile.DDL = (tables ++ mappingTables).map(_.schema).reduce(_ ++ _)

  val dbConfig = DatabaseConfig.forConfig[MySQLProfile]("pekko.projection.slick")
  // val dbConfig = DatabaseConfig.forConfig[PostgresProfile]("pekko.projection.slick")

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
      .map(_ => Done)
      .recover { case NonFatal(ex) =>
        ex.printStackTrace()
        Done
      }(sys.executionContext)

  def createAllTables1()(implicit sys: ActorSystem[_]): Future[Done] =
    SchemaUtils
      .createIfNotExists()
      .flatMap(_ => db.run(ddl.createIfNotExists))
      .flatMap(_ => SlickProjection.createTablesIfNotExists(dbConfig))
}

object RelationalData extends SlickTablesGeneric(slick.jdbc.MySQLProfile)
