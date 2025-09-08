ThisBuild / version := "0.1.0"

ThisBuild / scalaVersion := "2.13.16"

/*
val AkkaVersion = "2.6.21"
val akkaMngVersion = "1.1.4"
val AkkaHttpVersion = "10.2.10"
val typesafeConfigVersion = "1.4.2"
val AkkaProjectionVersion = "1.2.5" //"1.3.0"
val AkkaPersistenceJdbcV = "5.0.4"
*/

val ProjectName = "unique-definition-mng"
val AmmoniteVersion = "3.0.2"

val pekkoV = "1.2.0"
//https://github.com/apache/pekko-http/tags
val pekkoHttpV = "1.2.0"
//https://github.com/apache/pekko-management/tags
val PekkoManagementVersion = "1.1.1"
//val PekkoProjectionVersion = "1.1.0"
val PekkoProjectionVersion = "1.0.0" //1.1.0
val jdkV = "17"

lazy val java17Settings = Seq(
  "-XX:+PrintCommandLineFlags",
  //"-XshowSettings:system -version",
  "-XshowSettings:all",
  "--add-opens",
  "java.base/java.nio=ALL-UNNAMED",
  "--add-opens",
  "java.base/sun.nio.ch=ALL-UNNAMED"
)

lazy val root = (project in file("."))
  .settings(
    name := "unique-definition-management",
    javaOptions ++= java17Settings,
    Compile / javacOptions ++= Seq("-Xlint:unchecked", "-Xlint:deprecation", "-parameters"),
    javacOptions ++= Seq("-source", jdkV, "-target", jdkV),

    //show javacOptions
    //show scalacOptions

    javaHome := Some(file(s"/Library/Java/JavaVirtualMachines/jdk-${jdkV}.jdk/Contents/Home/")),

    Compile / scalacOptions ++= Seq(
      "-Xsource:3-cross",
      "-Wconf:msg=lambda-parens:s",
      s"-Wconf:src=${(Compile / target).value}/scala-2.13/akka-grpc/.*:silent",
      "-Wconf:msg=Marked as deprecated in proto file:silent",
      "-release:" + jdkV,
      "-Xlog-reflective-calls",
      "-Xlint",
      "-Xmigration", //Emit migration warnings under -Xsource:3 as fatal warnings, not errors; -Xmigration disables fatality (#10439 by @som-snytt, #10511)
      "-Vimplicits", // makes the compiler print implicit resolution chains when no implicit value can be found
      "-Ylog-classpath", //log classpath
    )
  )
  .enablePlugins(PekkoGrpcPlugin, JavaAppPackaging, DockerPlugin)

shellPrompt := { state => s"${SbtUtils.prompt(ProjectName)}> " }

//https://pekko.apache.org/docs/pekko/current/release-notes/releases-1.2.html
//https://pekko.apache.org/docs/pekko-persistence-r2dbc/current/query.html#eventsbyslices
libraryDependencies ++= Seq(
  //  show dependencyList
  "org.apache.pekko" %% "pekko-http" % pekkoHttpV,
  "org.apache.pekko" %% "pekko-http-spray-json"% pekkoHttpV,

  //"org.apache.pekko" %% "pekko-protobuf-v3" % pekkoV,
  "org.apache.pekko" %% "pekko-actor-typed" % pekkoV,
  "org.apache.pekko" %% "pekko-cluster-sharding-typed" % pekkoV,

  "org.apache.pekko" %% "pekko-distributed-data" % pekkoV,
  "org.apache.pekko" %% "pekko-persistence-typed" % pekkoV,
  "org.apache.pekko" %% "pekko-stream-typed" % pekkoV,

  "org.apache.pekko" %% "pekko-coordination" % pekkoV,
  "org.apache.pekko" %% "pekko-cluster-metrics" % pekkoV,

  "org.apache.pekko" %% "pekko-management" % PekkoManagementVersion,
  "org.apache.pekko" %% "pekko-management-cluster-bootstrap" % PekkoManagementVersion,
  "org.apache.pekko" %% "pekko-management-cluster-http" % PekkoManagementVersion,

  //protobuf-java-3.21.12.jar, jar org = com.google.protobuf, entry target = google/protobuf/struct.proto

  //"com.mysql" % "mysql-connector-j" % "9.4.0",
  //"io.asyncer" % "r2dbc-mysql" % "1.4.1",

  "org.apache.pekko" %% "pekko-persistence-query" % pekkoV,

  //"org.apache.pekko" %% "pekko-projection-slick" % PekkoProjectionVersion,
  "org.apache.pekko" %% "pekko-projection-core" % PekkoProjectionVersion,
  "org.apache.pekko" %% "pekko-projection-eventsourced" % PekkoProjectionVersion,
  //https://pekko.apache.org/docs/pekko-projection/current/durable-state.html
  //"org.apache.pekko" %% "pekko-projection-durable-state" % PekkoProjectionVersion,

  //https://pekko.apache.org/docs/pekko-persistence-r2dbc/current/query.html#publish-events-for-lower-latency-of-eventsbyslices
  "org.apache.pekko" %% "pekko-persistence-r2dbc" % "1.0.0",
  "org.apache.pekko" %% "pekko-projection-r2dbc"  % "1.0.0",
  
  "org.apache.pekko" %% "pekko-slf4j" % pekkoV,
  "ch.qos.logback" % "logback-classic" %  "1.5.18",
  "org.slf4j"      % "slf4j-api"       %  "2.0.17",


  "io.aeron" % "aeron-driver" % "1.46.9", //is jdk17 only
  "io.aeron" % "aeron-client" % "1.46.9",

  "com.lihaoyi" % "ammonite" % AmmoniteVersion % "test" cross CrossVersion.full
)


addCommandAlias("c", "compile")
addCommandAlias("r", "reload")

enablePlugins(JavaAppPackaging, DockerPlugin)
dockerBaseImage := "haghard/jdk17-open-table:1.0.1"
dockerRepository := Some("haghard")

Docker / daemonUserUid := None
dockerExposedPorts := Seq(8080, 8558, 25520)
Docker / daemonUser := "root"
Docker / daemonUserUid := None
// Publish settings
Compile / packageDoc / publishArtifact := false // speed up building Docker images
Compile / packageSrc / publishArtifact := false // speed up building Docker images


//dockerBaseImage := "docker.io/library/adoptopenjdk:14-jre-hotspot"
//dockerUsername := sys.props.get("docker.username")
//dockerRepository := sys.props.get("docker.registry")
dockerUpdateLatest := true
ThisBuild / dynverSeparator := "-"


scalafmtOnCompile := true

run / fork := false
//run / fork := true

//Global / cancelable := false // ctrl-c

dependencyOverrides ++= Seq(
  "org.apache.pekko" %% "pekko-discovery" % pekkoV,
  "org.apache.pekko" %% "pekko-protobuf-v3" % pekkoV,
  "org.apache.pekko" %% "pekko-actor-typed" % pekkoV,
  "org.apache.pekko" %% "pekko-cluster-sharding-typed" % pekkoV,
  "org.apache.pekko" %% "pekko-distributed-data" % pekkoV,
  "org.apache.pekko" %% "pekko-persistence-typed" % pekkoV,
  "org.apache.pekko" %% "pekko-stream-typed" % pekkoV,
  "org.apache.pekko" %% "pekko-slf4j" % pekkoV,

  "org.apache.pekko" %% "pekko-coordination" % pekkoV,
  "org.apache.pekko" %% "pekko-management" % PekkoManagementVersion,
  "org.apache.pekko" %% "pekko-management-cluster-bootstrap" % PekkoManagementVersion,
  "org.apache.pekko" %% "pekko-management-cluster-http" % PekkoManagementVersion,
)

//test:run
Test / sourceGenerators += Def.task {
  val file = (Test / sourceManaged).value / "amm.scala"
  IO.write(file, """object amm extends App { ammonite.Main().run() }""")
  Seq(file)
}.taskValue
