package com.definition

import org.apache.pekko.actor.typed.ActorSystem
import com.typesafe.config.*
import java.io.File

object App extends Ops {

  def main(args: Array[String]): Unit = {

    val AkkaSystemName     = "unique-definition-management"
    val HTTP_PORT_VAR      = "GRPC_PORT"
    val CONTACT_POINTS_VAR = "CONTACT_POINTS"

    val opts: Map[String, String] = argsToOpts(args.toList)
    applySystemProperties(opts)

    val configFile = new File("./src/main/resources/application.conf")

    val akkaPort = sys.props
      .get("pekko.remote.artery.canonical.port")
      .flatMap(_.toIntOption)
      .getOrElse(throw new Exception("akka.remote.artery.canonical.port not found"))

    val grpcPort = sys.props
      .get(HTTP_PORT_VAR)
      .flatMap(_.toIntOption)
      .getOrElse(throw new Exception(s"$HTTP_PORT_VAR not found"))

    val contactPoints =
      sys.props
        .get(CONTACT_POINTS_VAR)
        .map(_.split(","))
        .getOrElse(throw new Exception(s"$CONTACT_POINTS_VAR not found"))

    if (contactPoints.size != 2)
      throw new Exception(s"$CONTACT_POINTS_VAR expected size should be 2")

    val hostName = sys.props
      .get("pekko.remote.artery.canonical.hostname")
      .getOrElse(throw new Exception("pekko.remote.artery.canonical.hostname is expected"))

    val dockerHostName = internalDockerAddr.map(_.getHostAddress).getOrElse(hostName)

    val managementPort = grpcPort - 1
    applySystemProperties(
      Map(
        "-Dpekko.management.http.hostname"      -> hostName,
        "-Dpekko.management.http.port"          -> managementPort.toString,
        "-Dpekko.remote.artery.bind.hostname"   -> dockerHostName,
        "-Dpekko.remote.artery.bind.port"       -> akkaPort.toString,
        "-Dpekko.management.http.bind-hostname" -> dockerHostName,
        "-Dpekko.management.http.bind-port"     -> managementPort.toString
      )
    )

    val config: Config = {
      val bootstrapEndpoints = {
        val endpointList = contactPoints.map(s => s"{host=$s,port=$managementPort}").mkString(",")
        ConfigFactory
          .parseString(s"pekko.discovery.config.services { $AkkaSystemName = { endpoints = [ $endpointList ] }}")
          .resolve()
      }
      bootstrapEndpoints
        .withFallback(ConfigFactory.parseFile(configFile).resolve())
        .withFallback(ConfigFactory.load())
    }

    val system = ActorSystem[Nothing](Guardian(grpcPort), AkkaSystemName, config)

    org.apache.pekko.management.scaladsl.PekkoManagement(system).start()
    org.apache.pekko.management.cluster.bootstrap.ClusterBootstrap(system).start()
    org.apache.pekko.discovery.Discovery(system).loadServiceDiscovery("config") // kubernetes-api

    // TODO: for local debug only !!!!!!!!!!!!!!!!!!!
    val _ = scala.io.StdIn.readLine()
    system.log.warn("★ ★ ★ ★ ★ ★  Shutting down ... ★ ★ ★ ★ ★ ★")
    system.terminate()
    scala.concurrent.Await.result(
      system.whenTerminated,
      scala.concurrent.duration
        .DurationLong(
          config
            .getDuration(
              "pekko.coordinated-shutdown.default-phase-timeout",
              java.util.concurrent.TimeUnit.SECONDS
            )
        )
        .seconds
    )
  }
}
