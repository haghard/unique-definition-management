ThisBuild / version := "0.1.0"

ThisBuild / scalaVersion := "2.13.18"

/*
Akka-2.7.0(22.10)
https://doc.akka.io/reference/release-notes/
*/

val AkkaVersion = "2.7.0"
val AkkaHttpVersion = "10.4.0"
val AkkaManagementVersion = "1.2.0"
val AkkaPersistenceJdbcV = "5.2.0"
val AkkaPersistenceR2dbcVersion = "1.0.0"
val AkkaProjectionVersion = sys.props.getOrElse("akka-projection.version", "1.3.0")

lazy val java17Settings = Seq(
  "--add-opens",
  "java.base/java.nio=ALL-UNNAMED",
  "--add-opens",
  "java.base/sun.nio.ch=ALL-UNNAMED"
)

lazy val root = (project in file("."))
  .settings(
    name := "unique-definition-management",
    javaOptions ++= java17Settings,
  )
  .enablePlugins(AkkaGrpcPlugin)

//https://mvnrepository.com/artifact/com.lihaoyi/ammonite
//https://github.com/com-lihaoyi/Ammonite/releases
val AmmoniteVersion = "3.0.5"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-slf4j"  % AkkaVersion,
  "com.typesafe.akka" %% "akka-persistence-typed"       % AkkaVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding-typed"  % AkkaVersion,
  //"com.typesafe.akka" %% "akka-persistence-query" % AkkaVersion,
  "com.typesafe.akka" %% "akka-cluster-tools"           % AkkaVersion,

  "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
  
  "com.lightbend.akka" %% "akka-projection-eventsourced" % AkkaProjectionVersion,

  "org.hdrhistogram" % "HdrHistogram" % "2.2.2",

  "com.typesafe.akka"             %% "akka-discovery"               % AkkaVersion,
  "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % AkkaManagementVersion,
  "com.lightbend.akka.management" %% "akka-management-cluster-http" % AkkaManagementVersion,


  "ch.qos.logback" % "logback-classic" % "1.5.23",
  "org.slf4j"      % "slf4j-api"       %  "2.0.17",

  "io.aeron" % "aeron-driver" % "1.46.9", //1.47.0
  "io.aeron" % "aeron-client" % "1.46.9",

  //"org.wvlet.airframe" %% "airframe-ulid" % "2025.1.14",

  "mysql" % "mysql-connector-java" % "8.0.33",
  "com.lightbend.akka" %% "akka-persistence-jdbc" % AkkaPersistenceJdbcV,

  "com.lightbend.akka" %% "akka-projection-eventsourced" % AkkaProjectionVersion,
  "com.lightbend.akka" %% "akka-projection-slick" % AkkaProjectionVersion,

  //"com.github.jaceksokol" %% "akka-stream-map-async-partition" % "1.0.3",
  "com.lihaoyi" % "ammonite" % AmmoniteVersion % "test" cross CrossVersion.full
)

addCommandAlias("c", "compile")
addCommandAlias("r", "reload")


enablePlugins(JavaAppPackaging, DockerPlugin)
dockerBaseImage := "docker.io/library/adoptopenjdk:14-jre-hotspot"
dockerUsername := sys.props.get("docker.username")
dockerRepository := sys.props.get("docker.registry")
dockerUpdateLatest := true
ThisBuild / dynverSeparator := "-"

Compile / scalacOptions ++= Seq(
  "-Xsource:3-cross",
  "-Wconf:msg=lambda-parens:s",
  s"-Wconf:src=${(Compile / target).value}/scala-2.13/akka-grpc/.*:silent",
  "-Wconf:msg=Marked as deprecated in proto file:silent",
  //"-release:14",
  "-Xlog-reflective-calls",
  "-Xlint",
  "-Xmigration", //Emit migration warnings under -Xsource:3 as fatal warnings, not errors; -Xmigration disables fatality (#10439 by @som-snytt, #10511)
  "-Vimplicits", // makes the compiler print implicit resolution chains when no implicit value can be found
  "-Ylog-classpath", //log classpath
)

Compile / javacOptions ++= Seq("-Xlint:unchecked", "-Xlint:deprecation", "-parameters")

scalafmtOnCompile := true

run / fork := false
//run / fork := true

//Global / cancelable := false // ctrl-c

dependencyOverrides ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed"             % AkkaVersion,
  "com.typesafe.akka" %% "akka-protobuf"                % AkkaVersion,
  "com.typesafe.akka" %% "akka-protobuf-v3"             % AkkaVersion,
  "com.typesafe.akka" %% "akka-persistence"             % AkkaVersion,
  "com.typesafe.akka" %% "akka-actor"                   % AkkaVersion,
  "com.typesafe.akka" %% "akka-cluster"                 % AkkaVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding-typed"  % AkkaVersion,
  "com.typesafe.akka" %% "akka-coordination"            % AkkaVersion,
  "com.typesafe.akka" %% "akka-stream"                  % AkkaVersion,
  "com.typesafe.akka" %% "akka-cluster-tools"           % AkkaVersion,
  "com.typesafe.akka" %% "akka-http"                    % AkkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-core"               % AkkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json"         % AkkaHttpVersion,
)

//test:run
Test / sourceGenerators += Def.task {
  val file = (Test / sourceManaged).value / "amm.scala"
  IO.write(file, """object amm extends App { ammonite.Main().run() }""")
  Seq(file)
}.taskValue
