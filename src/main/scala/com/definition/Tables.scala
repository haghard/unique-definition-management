package com.definition

import akka.Done
import akka.actor.typed.ActorSystem
import akka.projection.slick.SlickProjection
import com.definition.api.{PutReply, PutRequest}
import com.definition.domain.event.ConflictTag
import com.definition.domain.*
import scalapb.*
import slick.basic.DatabaseConfig
import slick.jdbc.{GetResult, MySQLProfile, PostgresProfile}

import java.util.UUID
import scala.concurrent.*
import scala.reflect.ClassTag
import scala.util.Using

sealed trait RequestResult

object RequestResult {
  final case class Ok(definitionLocation: DefinitionLocation) extends RequestResult

  final case object Placed extends RequestResult

  final case class Resend(request: PutRequest) extends RequestResult

  final case class ResendInFlightRequestOnConflict(request: PutRequest) extends RequestResult

  final case object ConcurrentModification extends RequestResult

  final case class OwnerReserved(entityId: Long, seqNum: Long) extends RequestResult

  final case object LocationNotFound extends RequestResult

  final case object NotFound extends RequestResult
}

final case class DefinitionRow(
  name: String,
  definition: Definition,
  ownerId: UUID,
  bucketId: Long,
  sequenceNr: Long,
  ts: Long
)

sealed abstract case class RequestTag(val id: Int)

object RequestTag {
  implicit object Create extends RequestTag(0)

  implicit object Update extends RequestTag(1)
}

final case class RequestRow(ownerId: UUID, request: PutRequest, tag: Int, when: Long)

class SlickTablesGeneric(val profile: slick.jdbc.MySQLProfile)(implicit ec: ExecutionContext) {

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

  class Definitions(tag: Tag) extends Table[DefinitionRow](tag, "definitions") {

    // for debug only
    def name: Rep[String] = column[String]("NAME", O.Length(400))

    def definition: Rep[Definition] = column[Definition]("DEFINITION")

    def ownerId: Rep[UUID] = column[UUID]("OWNER_ID")

    def bucketId: Rep[Long] = column[Long]("HASH_BUCKET_ID")

    def sequenceNr: Rep[Long] = column[Long]("SEQ_NUM")

    def time: Rep[Long] = column[Long]("TIME")

    def pk: slick.lifted.PrimaryKey = primaryKey("div__pk", (bucketId, sequenceNr))

    def ownerIdIndex: slick.lifted.Index = index("div__owner_id_index", ownerId, unique = true)

    def * : slick.lifted.ProvenShape[DefinitionRow] =
      (name, definition, ownerId, bucketId, sequenceNr, time) <>
        ((DefinitionRow.apply _).tupled, DefinitionRow.unapply)
  }

  object definitions extends TableQuery(new Definitions(_)) {
    self =>

    val locationDefinition = Compiled { (ownerId: Rep[UUID]) =>
      self
        .filter(_.ownerId === ownerId)
        .map(rep => (rep.bucketId, rep.sequenceNr, rep.definition))
    }

    def getLocationDefinition(ownerId: UUID): Future[Option[(Long, Long, Definition)]] =
      db.run(locationDefinition(ownerId).result.headOption)

    def create(row: DefinitionRow): Future[Int] = {
      val create         = self.insertOrUpdate(row)
      val releaseRequest = requestsInFlight.filter(_.ownerId === row.ownerId).delete
      db.run((create >> releaseRequest).transactionally)
    }

    def update(row: DefinitionRow): Future[Int] = {
      val update =
        self
          .filter(rep => rep.ownerId === row.ownerId)
          .map(rep => (rep.bucketId, rep.sequenceNr, rep.definition, rep.name, rep.time))
          .update((row.bucketId, row.sequenceNr, row.definition, row.name, row.ts))

      val releaseRequest = requestsInFlight.filter(_.ownerId === row.ownerId).delete
      db.run((update >> releaseRequest).transactionally)
    }

    def release(ownerId: UUID, conflictTag: ConflictTag): Future[Int] =
      conflictTag match {
        case ConflictTag.Create =>
          db.run(
            requestsInFlight.filter(rep => rep.ownerId === ownerId && rep.requestTag === RequestTag.Create.id).delete
          )
        case ConflictTag.Update =>
          db.run(
            requestsInFlight.filter(rep => rep.ownerId === ownerId && rep.requestTag === RequestTag.Update.id).delete
          )
        case ConflictTag.Unspecified | ConflictTag.Unrecognized(_) =>
          Future.successful(-1)
      }
  }

  class RequestInFlight(tag: Tag) extends Table[RequestRow](tag, "request_in_flight") {

    def ownerId: Rep[UUID] = column[UUID]("OWNER_ID")

    def request: Rep[PutRequest] = column[PutRequest]("REQUEST")

    def requestTag: Rep[Int] = column[Int]("TAG")

    def when: Rep[Long] = column[Long]("WHEN")

    def pk: slick.lifted.PrimaryKey = primaryKey("request_in_flight__pk_owner_id", ownerId)

    def * : slick.lifted.ProvenShape[RequestRow] =
      (ownerId, request, requestTag, when) <>
        ((RequestRow.apply _).tupled, RequestRow.unapply)
  }

  object requestsInFlight extends TableQuery(new RequestInFlight(_)) {
    self =>

    def put[T <: RequestTag](
      ownerId: UUID,
      request: PutRequest
    )(implicit requestTag: T): DBIO[RequestResult] =
      self
        .filter(rep => rep.ownerId === ownerId)
        .result
        .headOption
        .flatMap {
          case None =>
            self
              .+=(RequestRow(ownerId, request, requestTag.id, System.currentTimeMillis()))
              .map(_ => RequestResult.Placed)

          case Some(existingRow) =>
            if (existingRow.tag == requestTag.id) {
              val result =
                if (request == existingRow.request)
                  RequestResult.Resend(request)
                else
                  RequestResult.ResendInFlightRequestOnConflict(existingRow.request)

              DBIO.successful(result)
            } else {
              DBIO.successful(RequestResult.ConcurrentModification)
            }
        }
  }

  def create(
    in: PutRequest
  )(ask: PutRequest => Future[PutReply]): Future[PutReply] = {
    val ownerId = UUID.fromString(in.ownerId)
    val dbio    =
      definitions
        .locationDefinition(ownerId)
        .result
        .headOption
        .flatMap {
          case None =>
            requestsInFlight.put[RequestTag.Create.type](ownerId, in)

          case Some(row) =>
            val (entityId, seqNum, definition) = row
            if (definition == in.definition) {
              DBIO.successful(RequestResult.Ok(DefinitionLocation(entityId, seqNum)))
            } else {
              DBIO.successful(RequestResult.OwnerReserved(entityId, seqNum))
            }
        }
        .transactionally

    db.run(dbio)
      .flatMap {
        case RequestResult.Ok(location) =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.OKNoOp,
              location
            )
          )

        case RequestResult.Placed =>
          ask(in)

        case RequestResult.Resend(request) =>
          ask(request)

        case RequestResult.ResendInFlightRequestOnConflict(request) =>
          ask(request).flatMap(_ =>
            Future.successful(
              PutReply(
                in.ownerId,
                PutReply.StatusCode.ConcurrentModification,
                DefinitionLocation(-1, -1)
              )
            )
          )

        case RequestResult.ConcurrentModification =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.ConcurrentModification,
              DefinitionLocation(-1, -1)
            )
          )

        case RequestResult.OwnerReserved(entityId, seqNum) =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.OwnerReserved,
              DefinitionLocation(entityId, seqNum)
            )
          )

        case RequestResult.LocationNotFound | RequestResult.NotFound =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.Unrecognized(-1),
              DefinitionLocation(-1, -1)
            )
          )
      }
  }

  def update(
    in: PutRequest
  )(ask: (PutRequest, DefinitionLocation) => Future[PutReply]): Future[PutReply] = {
    val ownerId = UUID.fromString(in.ownerId)

    val dbio: DBIO[RequestResult] =
      definitions
        .locationDefinition(ownerId)
        .result
        .headOption
        .flatMap {
          case None =>
            DBIO.successful(RequestResult.NotFound)

          case Some(row) =>
            val (hashBucketId, seqNum, definition) = row
            val currentLocation                    = DefinitionLocation(hashBucketId, seqNum)
            if (in.getLocation == currentLocation) {
              if (in.definition == definition) {
                DBIO.successful(RequestResult.Ok(currentLocation))
              } else {
                requestsInFlight.put[RequestTag.Update.type](ownerId, in)
              }
            } else {
              DBIO.successful(RequestResult.LocationNotFound)
            }
        }

    val dbOp: Future[RequestResult] = db.run(dbio)
    dbOp
      .flatMap {
        case RequestResult.Ok(location) =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.OKNoOp,
              location
            )
          )

        case RequestResult.Placed =>
          ask(in, in.getLocation)

        case RequestResult.Resend(reqInFlight) =>
          ask(reqInFlight, reqInFlight.getLocation)

        case RequestResult.ResendInFlightRequestOnConflict(reqInFlight) =>
          ask(reqInFlight, reqInFlight.getLocation).flatMap(_ =>
            Future.successful(
              PutReply(
                in.ownerId,
                PutReply.StatusCode.ConcurrentModification,
                DefinitionLocation(-1, -1)
              )
            )
          )

        case RequestResult.ConcurrentModification =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.ConcurrentModification,
              DefinitionLocation(-1, -1)
            )
          )

        case RequestResult.NotFound =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.OwnerNotFound,
              DefinitionLocation(-1, -1)
            )
          )

        case RequestResult.LocationNotFound =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.LocationNotFound,
              DefinitionLocation(-1, -1)
            )
          )

        case _: RequestResult.OwnerReserved =>
          Future.successful(
            PutReply(
              in.ownerId,
              PutReply.StatusCode.Unrecognized(-1),
              DefinitionLocation(-1, -1)
            )
          )
      }
  }

  val tables           = Seq(requestsInFlight, definitions) // definition0, definition1, definition2, definition3
  val ddl: profile.DDL = tables.map(_.schema).reduce(_ ++ _)

  /*val psgDatabaseConfig = new DatabaseConfig[PostgresProfile] {
    val profile = PostgresProfile
    def db = database.asInstanceOf[profile.backend.Database]
    def config = ConfigFactory.empty
    def profileName = "slick.jdbc.PostgresProfile"
    def profileIsObject = false
  }*/

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

  def createTables()(implicit sys: ActorSystem[_]): Future[Done] =
    db.run(ddl.createIfNotExists)
      .flatMap(_ => SlickProjection.createTablesIfNotExists(dbConfig))
}

object Tables extends SlickTablesGeneric(slick.jdbc.MySQLProfile)(ExecutionContext.parasitic)
