package com.definition

import org.HdrHistogram.Histogram
import org.slf4j.LoggerFactory

trait Measure {

  private val log = LoggerFactory.getLogger("EH")

  // JVM System property
  private val lagThresholdMillis = Integer.getInteger("lag-threshold-ms", 2000)

  private var reportingStartTime = System.nanoTime()

  private var totalCount      = 0
  private var throughputCount = 0
  private var lagCount        = 0L
  private var reportingCount  = 0

  private val percentiles          = List(50.0, 75.0, 90.0, 95.0, 99.0, 99.9)
  private val maxHistogramValue    = 60L * 1000L
  private var histogram: Histogram = new Histogram(maxHistogramValue, 2)

  def logId: String

  def processedEvent(eventTimestamp: Long): Unit = {
    totalCount += 1

    val lagMillis = System.currentTimeMillis() - eventTimestamp

    histogram.recordValue(math.max(0L, math.min(lagMillis, maxHistogramValue)))

    throughputCount += 1
    val durationMs: Long =
      (System.nanoTime - reportingStartTime) / 1000 / 1000
    // more frequent reporting in the beginning
    val reportAfter =
      if (reportingCount <= 5) 30000 else 180000
    if (durationMs >= reportAfter) {
      reportingCount += 1

      log.info(
        s"$logId #$reportingCount: Processed ${histogram.getTotalCount} events in $durationMs ms, " +
          s"throughput [${1000L * throughputCount / durationMs}] events/s, " +
          s"max lag [${histogram.getMaxValue}] ms, " +
          s"lag percentiles [${percentiles
              .map(p => s"$p%=${histogram.getValueAtPercentile(p)}ms")
              .mkString("; ")}]"
      )
      println(s"$logId #$reportingCount: HDR histogram [${percentiles
          .map(p => s"$p%=${histogram.getValueAtPercentile(p)}ms")
          .mkString("; ")}]")
      histogram.outputPercentileDistribution(System.out, 1.0)

      throughputCount = 0
      histogram = new Histogram(maxHistogramValue, 2)
      reportingStartTime = System.nanoTime
    }

    if (lagMillis > lagThresholdMillis) {
      lagCount += 1
      if ((lagCount == 1) || (lagCount % 1000 == 0))
        log.info("Projection [{}] lag [{}] ms. Total [{}] events.", logId, lagMillis, totalCount)
    } else {
      lagCount = 0
    }

  }
}

// works
//import pekko.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
//import pekko.projection.r2dbc.scaladsl.{R2dbcProjection, R2dbcSession}
//import pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
//import pekko.projection.scaladsl.{Handler, SourceProvider}
//import pekko.persistence.query.typed.EventEnvelope
//import org.apache.pekko.persistence.query.typed.EventEnvelope
//import org.apache.pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
//import org.apache.pekko.serialization.{Serialization, SerializationExtension}

/*def initProjections(takenDefinitions: ActorRef[Cmd])(implicit system: ActorSystem[_]): Unit = {
  // val dbConfig = DatabaseConfig.forConfig[ MySQLProfile]("akka.projection.slick")
  // val dbConfig = DatabaseConfig.forConfig[slick.jdbc.PostgresProfile]("akka.projection.slick")
  /*val eventQueries = PersistenceQuery(system).readJournalFor[R2dbcReadJournal](R2dbcReadJournal.Identifier)
  eventQueries
    .eventsBySlices(
      TakenDefinition.TypeKey.name,
      0,
      255,
      NoOffset.getInstance /*org.apache.pekko.persistence.query.Offset.noOffset*/
    )
    .runWith(Sink.foreach { env: EventEnvelope[Event] => println("***>>>>>>>>>" + env) })*/

  implicit val resolver: ActorRefResolver = ActorRefResolver(system)
  val slices                              = EventSourcedProvider.sliceRanges(
    system,
    JdbcReadJournal.Identifier,
    numberOfSlices
  ) // R2dbcReadJournal.Identifier
  val dbConfig: DatabaseConfig[PostgresProfile] = DatabaseConfig.forConfig[PostgresProfile]("akka.projection.slick")

  /*ConnectionFactories.get(ConnectionFactoryOptions.builder()
    .option(DRIVER, "postgresql")
    .option(HOST, "...")
    .option(PORT, 5432)  // optional, defaults to 5432
    .option(USER, "...")
    .option(PASSWORD, "...")
    .option(DATABASE, "...")  // optional
    //.option(OPTIONS, options) // optional
    .build())*/

  ShardedDaemonProcess(system).init(
    projectionName,
    numberOfSlices,
    i => {
      val slice         = slices(i)
      val projectionKey = s"${TakenDefinition.TypeKey.name}-${slice.min}-${slice.max}"
      val projectionId  = ProjectionId.of(projectionName, projectionKey)
      val sourceProvider: SourceProvider[Offset, EventEnvelope[Event]] =
        EventSourcedProvider.eventsBySlices[Event](
          system,
          JdbcReadJournal.Identifier,
          TakenDefinition.TypeKey.name,
          slice.min,
          slice.max
        )
      ProjectionBehavior(
        SlickProjection.atLeastOnceAsync(
          // R2dbcProjection.exactlyOnce( // R2dbcProjection.atLeastOnce(
          projectionId,
          // None,
          sourceProvider,
          dbConfig,
          () =>
            (env: EventEnvelope[Event]) =>
              env.event match {
                case a: Acquired =>
                  a.prevDefinitionLocation match {
                    case Some(prevDefinitionLocation) =>
                      takenDefinitions.askWithStatus[Done](replyTo =>
                        com.definition.domain.command.Replace(
                          ownerId = a.ownerId,
                          definition = a.definition,
                          acquiredSeqNum = a.seqNum,
                          acquiredBucketId = env.persistenceId.toLong,
                          prevDefinitionLocation = prevDefinitionLocation,
                          replyTo = resolver.toSerializationFormat(replyTo)
                        )
                      )

                    case None =>
                      val row =
                        DefinitionIndexViewRow(
                          name = a.definition.name,
                          definition = a.definition,
                          ownerId = UUID.fromString(a.ownerId),
                          bucketId = env.persistenceId.toLong,
                          sequenceNr = a.seqNum,
                          when = env.timestamp
                        )
                      RelationalData.definitionIndexView.createAndUnlock(row)
                  }

                case r: Released =>
                  val row =
                    DefinitionIndexViewRow(
                      name = r.definition.name,
                      definition = r.definition,
                      ownerId = UUID.fromString(r.ownerId),
                      bucketId = r.acquiredBucketId,
                      sequenceNr = r.acquiredSeqNum,
                      when = env.timestamp
                    )
                  RelationalData.definitionIndexView.updateAndUnlock(row)

              }
          // () => new EventHandler(projectionId, region)
          /*(session: R2dbcSession, env: EventEnvelope[Event]) =>
              Future.successful {
                val id = PersistenceId.extractEntityId(env.persistenceId)
                println(id + " <===> " + env.event)
                Done
                /*if (ThreadLocalRandom.current().nextBoolean()) throw new Exception("Boom :" + id)
                else {
                  println("***====" + id)
                  Done
                }*/
              }*/
        )
      )
    },
    ShardedDaemonProcessSettings(system),
    Some(ProjectionBehavior.Stop)
  )
}*/
