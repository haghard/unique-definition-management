package com.definition

import akka.actor.typed.ActorSystem
import akka.persistence.r2dbc.ConnectionFactoryProvider
import akka.persistence.r2dbc.internal.R2dbcExecutor
import akka.persistence.r2dbc.internal.R2dbcExecutor.PublisherOps
import akka.persistence.r2dbc.internal.Sql.Interpolation
import akka.projection.r2dbc.R2dbcProjectionSettings
import com.definition.api.{GetDefinitionLocationReply, PutRequest}
import com.definition.domain.event.ConflictTag
import com.definition.domain.{Definition, DefinitionLocation}
import io.r2dbc.spi.Connection
import org.slf4j.LoggerFactory

import scala.concurrent.*

//https://github.com/pgjdbc/r2dbc-postgresql
final class R2dbcDao(system: ActorSystem[?]) {

  val r2dbcSettings     = R2dbcProjectionSettings(system)
  val connectionFactory = ConnectionFactoryProvider(system).connectionFactoryFor(r2dbcSettings.useConnectionFactory)

  /*connectionFactory
    .create()
    .asFuture()
    .map { c =>
      c.beginTransaction(PostgresTransactionDefinition.from(IsolationLevel.REPEATABLE_READ))
    }*/

  val r2dbcExecutor =
    new R2dbcExecutor(
      connectionFactory,
      LoggerFactory.getLogger(classOf[R2dbcExecutor]),
      r2dbcSettings.logDbCallsExceeding
    )(system.executionContext, system)

  implicit val ex: ExecutionContext = ExecutionContext.parasitic

  def selectDefinition(tableName: String) =
    sql"SELECT hash_bucket_id, seq_num, definition FROM $tableName WHERE owner_id = CAST(? AS UUID)"

  // r2dbcExecutor.executeDdls(???)

  def getDefinition(ownerId: String, tableName: String): Future[GetDefinitionLocationReply] =
    r2dbcExecutor
      .selectOne("get")(
        con => con.createStatement(selectDefinition(tableName)).bind(0, ownerId),
        row => {
          val bucketId   = row.get("hash_bucket_id", classOf[java.lang.Long])
          val seqNum     = row.get("seq_num", classOf[java.lang.Long])
          val definition = Definition.parseFrom(row.get("definition", classOf[Array[Byte]]))
          GetDefinitionLocationReply(Some(DefinitionLocation(bucketId, seqNum)), Some(definition))
        }
      )
      .map(_.getOrElse(GetDefinitionLocationReply(Some(DefinitionLocation(-1, -1)), None)))

  private def placeRequest(con: Connection, in: PutRequest, conflictTag: ConflictTag): Future[RequestResult] =
    con
      .createStatement(sql"SELECT request, tag FROM pending_requests WHERE owner_id = CAST(? AS UUID)")
      .bind(0, in.ownerId)
      .execute()
      .asFuture()
      .flatMap { pendingRequests =>
        pendingRequests
          .map { pendingRequestsRow =>
            val tag        = pendingRequestsRow.get("tag", classOf[java.lang.Integer])
            val putRequest = PutRequest.parseFrom(pendingRequestsRow.get("request", classOf[Array[Byte]]))
            if (tag == conflictTag.value) {
              if (in == putRequest) RequestResult.Resend(putRequest)
              else RequestResult.ResendInFlightRequestOnConflict(putRequest)
            } else {
              RequestResult.ConcurrentModification
            }
          }
          .asFuture()
          .flatMap { result: RequestResult =>
            if (result ne null)
              Future.successful(result)
            else {
              con
                .createStatement(
                  sql"INSERT INTO pending_requests (owner_id, tag, request, ts) VALUES(CAST(? AS UUID),?,?,?)"
                )
                .bind(0, in.ownerId)
                .bind(1, conflictTag.value)
                .bind(2, in.toByteArray)
                .bind(3, System.currentTimeMillis())
                .execute()
                .asFuture()
                .flatMap(_.getRowsUpdated.asFuture().map { n =>
                  if (n == 1) RequestResult.Placed else RequestResult.ConcurrentModification
                })
            }
          }
      }

  def create(in: PutRequest, tableName: String): Future[RequestResult] =
    r2dbcExecutor
      .withAutoCommitConnection("create") { con =>
        con
          .createStatement(selectDefinition(tableName))
          .bind(0, in.ownerId)
          .execute()
          .asFuture()
          .flatMap { definitionResult =>
            val f: Future[RequestResult] =
              definitionResult
                .map { row =>
                  val bucketId   = row.get("hash_bucket_id", classOf[java.lang.Long])
                  val seqNum     = row.get("seq_num", classOf[java.lang.Long])
                  val definition = Definition.parseFrom(row.get("definition", classOf[Array[Byte]]))
                  if (definition == in.definition) {
                    RequestResult.Ok(DefinitionLocation(bucketId, seqNum))
                  } else {
                    RequestResult.OwnerReserved(bucketId, seqNum)
                  }
                }
                .asFuture()
                .flatMap { reply: RequestResult =>
                  if (reply == null) placeRequest(con, in, ConflictTag.Create)
                  else Future.successful(reply)
                }
            f
          }
      }

  def update(in: PutRequest, tableName: String): Future[RequestResult] =
    r2dbcExecutor
      .withAutoCommitConnection("update") { con =>
        con
          .createStatement(selectDefinition(tableName))
          .bind(0, in.ownerId)
          .execute()
          .asFuture()
          .flatMap { definitionResult =>
            definitionResult
              .map { row =>
                val bucketId        = row.get("hash_bucket_id", classOf[java.lang.Long])
                val seqNum          = row.get("seq_num", classOf[java.lang.Long])
                val definition      = Definition.parseFrom(row.get("definition", classOf[Array[Byte]]))
                val currentLocation = DefinitionLocation(bucketId, seqNum)
                if (in.getLocation == currentLocation) {
                  if (in.definition == definition) {
                    RequestResult.Ok(currentLocation)
                  } else {
                    RequestResult.Update
                  }
                } else {
                  RequestResult.LocationNotFound
                }
              }
              .asFuture()
              .flatMap { reply: RequestResult =>
                if (reply == null)
                  Future.successful(RequestResult.NotFound)
                else if (reply == RequestResult.Update)
                  placeRequest(con, in, ConflictTag.Update)
                else
                  Future.successful(reply)
              }
          }
      }
}
