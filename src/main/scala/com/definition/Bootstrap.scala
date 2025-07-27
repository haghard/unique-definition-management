package com.definition

import akka.Done
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.CoordinatedShutdown
import akka.actor.CoordinatedShutdown.*
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.*
import com.definition.service.*
import com.definition.service.DefinitionServiceImpl

import scala.concurrent.duration.Duration
import scala.concurrent.Future
import scala.util.*

object Bootstrap {

  private final case object BindFailure extends Reason
}

final case class Bootstrap(
  shardRegion: ActorRef[com.definition.domain.Cmd],
  bindHost: String,
  port: Int
)(implicit system: ActorSystem[_]) {
  import system.executionContext

  val config              = system.settings.config
  val terminationDeadline =
    Duration.fromNanos(config.getDuration("akka.coordinated-shutdown.default-phase-timeout").toNanos)

  val shutdown                                         = CoordinatedShutdown(system)
  val grpcService: HttpRequest => Future[HttpResponse] =
    DefinitionServiceHandler.withServerReflection(new DefinitionServiceImpl(shardRegion)(system))

  Http(system)
    .newServerAt(bindHost, port)
    .bind(grpcService)
    .onComplete {
      case Failure(ex) =>
        system.log.error(s"Shutting down because can't bind to $bindHost:$port", ex)
        shutdown.run(Bootstrap.BindFailure)
      case Success(binding) =>
        system.log.info("★ ★ ★ ★ ★ ★ ★ ★ ★ ActorSystem({}) tree ★ ★ ★ ★ ★ ★ ★ ★ ★", system.name)
        system.log.info(system.printTree)

        shutdown.addTask(PhaseBeforeServiceUnbind, "before-unbind") { () =>
          Future.successful {
            system.log.info("★ ★ ★ CoordinatedShutdown [before-unbind] ★ ★ ★")
            Done
          }
        }

        // Next 2 tasks(PhaseServiceUnbind, PhaseServiceRequestsDone) makes sure that during shutdown
        // no more requests are accepted and
        // all in-flight requests have been processed
        shutdown.addTask(PhaseServiceUnbind, "http-unbind") { () =>
          // No new connections are accepted. Existing connections are still allowed to perform request/response cycles
          binding.unbind().map { done =>
            system.log.info("★ ★ ★ CoordinatedShutdown [http-api.unbind] ★ ★ ★")
            done
          }
        }

        // graceful termination request being handled on this connection
        shutdown.addTask(PhaseServiceRequestsDone, "http-terminate") { () =>
          /** It doesn't accept new connection, but it drains the existing connections Until the `terminationDeadline`
            * all the req that have been accepted will be completed and only than the shutdown will continue
            */

          binding.terminate(terminationDeadline).map { _ =>
            system.log.info("★ ★ ★ CoordinatedShutdown [http-api.terminate]  ★ ★ ★")
            Done
          }
        }

        // forcefully kills connections that are still open
        shutdown.addTask(PhaseServiceStop, "close.http.connections") { () =>
          Http().shutdownAllConnectionPools().map { _ =>
            system.log.info("★ ★ ★ CoordinatedShutdown [close.http.connections] ★ ★ ★")
            Done
          }
        }

        shutdown.addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "close.db") { () =>
          Tables.shutdown().map {
            system.log.info("★ ★ ★ CoordinatedShutdown [close.db] ★ ★ ★")
            _ => Done
          }
        }

        shutdown.addTask(PhaseActorSystemTerminate, "system.term") { () =>
          Future.successful {
            system.log.info("★ ★ ★ CoordinatedShutdown [close.system] ★ ★ ★")
            Done
          }
        }
    }
}
