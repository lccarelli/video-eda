package dev.videoeda.videos.infrastructure

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.videoeda.videos.application.IdempotencyKey
import dev.videoeda.videos.application.PermanentProcessingException
import dev.videoeda.videos.application.ProcessVideoRequestHandler
import dev.videoeda.videos.application.Topics
import dev.videoeda.videos.application.VideoService
import dev.videoeda.videos.application.VideoTranscoder
import dev.videoeda.videos.domain.CorrelationId
import dev.videoeda.videos.domain.CreatorId
import dev.videoeda.videos.domain.VideoId
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

abstract class ProcessingWorkerScenario {
    protected val lucia = CreatorId("usr_01a13161-11be-7119-8e3e-bf07f13eb833")
    protected val correlation = CorrelationId("cor_01a13161-13c4-7752-b78c-4c2b8c8039fb")
    protected val clock = Clock.systemUTC()

    protected val testId = UUID.randomUUID().toString().take(8)
    protected val processingTopic = "videos.processing.$testId"
    protected val deadLetterTopic = "videos.processing.dlq.$testId"
    protected val groupId = "videos-processing-worker-$testId"
    protected val recordLockMillis = 3_000L

    protected val jdbc = PostgresFixture.jdbc
    protected val repository = JdbcVideoRepository(jdbc)
    protected val outbox = JdbcOutboxWriter(jdbc)
    protected val transaction = SpringTransaction(PostgresFixture.transactionManager)
    protected val videos = VideoService(repository, outbox, transaction, clock)

    protected val producers = mutableListOf<KafkaProducer<String, String>>()
    protected val workers = mutableListOf<ProcessVideoShareWorker>()
    protected val alertLogs = ListAppender<ILoggingEvent>()
    protected val alertLogger = LoggerFactory.getLogger(ProcessVideoShareWorker::class.java) as Logger

    @BeforeEach
    fun setUp() {
        PostgresFixture.resetTables()
        KafkaFixture.createTopic(deadLetterTopic)
        KafkaFixture.configureShareGroup(groupId, recordLockMillis)
        alertLogs.start()
        alertLogger.addAppender(alertLogs)
    }

    @AfterEach
    fun tearDown() {
        workers.forEach { it.stop() }
        producers.forEach { it.close() }
        alertLogger.detachAppender(alertLogs)
    }

    protected fun newProducer() = KafkaFixture.newProducer().also { producers += it }

    protected var processingTopicCreated = false

    protected fun createProcessingTopicSharedByAllWorkers() = createProcessingTopic(partitions = 1)

    protected fun createProcessingTopic(partitions: Int = 3) {
        KafkaFixture.createTopic(processingTopic, partitions)
        processingTopicCreated = true
    }

    protected fun startWorker(transcoder: VideoTranscoder) {
        if (!processingTopicCreated) createProcessingTopic()
        val handler = ProcessVideoRequestHandler(JdbcVideoQueries(jdbc), transcoder, videos)
        val worker = ProcessVideoShareWorker(
            consumer = newProcessingShareConsumer(KafkaFixture.bootstrapServers, groupId),
            handler = handler,
            deadLetters = KafkaDeadLetterPublisher(newProducer(), deadLetterTopic),
            maxDeliveries = 3,
            topic = processingTopic,
            pollTimeout = Duration.ofMillis(200),
        )
        workers += worker
        worker.start()
    }

    protected fun requestProcessing(title: String = "Paella valenciana"): VideoId {
        if (!processingTopicCreated) createProcessingTopic()
        val videoId = videos.upload(lucia, title, 1_048_576, IdempotencyKey(UUID.randomUUID().toString()), correlation).videoId
        val realSender = KafkaOutboxMessageSender(newProducer())
        val redirectingSender = object : OutboxMessageSender {
            override fun send(message: OutboxMessage) =
                realSender.send(if (message.topic == Topics.VIDEOS_PROCESSING) message.copy(topic = processingTopic) else message)
        }
        OutboxPublisher(PostgresFixture.dataSource, jdbc, redirectingSender).publishPendingEvents()
        return videoId
    }

    protected fun statusOf(videoId: VideoId) =
        jdbc.sql("SELECT status FROM video WHERE video_id = ?").param(videoId.value).query(String::class.java).single()

    protected fun videoPublishedCount(videoId: VideoId) = jdbc.sql("SELECT count(*) FROM outbox WHERE topic = ? AND aggregate_id = ?")
        .param(Topics.VIDEOS_VIDEO).param(videoId.value).query(Int::class.java).single()

    protected fun deadLettersOf(videoId: VideoId) = KafkaFixture.readAllRecords(deadLetterTopic).filter { it.key == videoId.value }

    protected fun waitForTheOtherWorkerToFinish() = Thread.sleep(8_000)

    protected fun awaitUntil(timeoutSeconds: Long = 40, description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "no se cumplió en $timeoutSeconds s: $description" }
            Thread.sleep(100)
        }
    }

    protected fun alertsLogged() = alertLogs.list.filter { it.level == Level.ERROR && it.mdcPropertyMap["alert"] == "true" }
}
