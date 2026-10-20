package dev.videoeda.videos.infrastructure

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.videoeda.videos.JsonSchemas
import dev.videoeda.videos.application.IdempotencyKey
import dev.videoeda.videos.application.Topics
import dev.videoeda.videos.application.VideoService
import dev.videoeda.videos.domain.CorrelationId
import dev.videoeda.videos.domain.CreatorId
import dev.videoeda.videos.domain.VideoId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

abstract class OutboxPublisherScenario {
    protected val lucia = CreatorId("usr_01a13161-11be-7119-8e3e-bf07f13eb833")
    protected val correlation = CorrelationId("cor_01a13161-13c4-7752-b78c-4c2b8c8039fb")
    protected val clock = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC)
    protected val jsonMapper = JsonMapper.builder().build()

    protected val jdbc = PostgresFixture.jdbc
    protected val repository = JdbcVideoRepository(jdbc)
    protected val outbox = JdbcOutboxWriter(jdbc)
    protected val transaction = SpringTransaction(PostgresFixture.transactionManager)
    protected val videos = VideoService(repository, outbox, transaction, clock)

    protected val producers = mutableListOf<org.apache.kafka.clients.producer.KafkaProducer<String, String>>()
    protected val executors = mutableListOf<java.util.concurrent.ExecutorService>()

    @BeforeEach fun clean() = PostgresFixture.resetTables()

    @AfterEach fun closeResources() {
        producers.forEach { it.close(java.time.Duration.ofSeconds(1)) }
        executors.forEach { it.shutdownNow() }
    }

    protected fun newPublisher(
        sender: OutboxMessageSender = KafkaOutboxMessageSender(
            KafkaFixture.newProducer().also {
                producers += it
            },
        ),
    ) = OutboxPublisher(PostgresFixture.dataSource, jdbc, sender, jsonMapper)

    protected fun newVideo(): VideoId =
        videos.upload(lucia, "Paella valenciana", 1_048_576, IdempotencyKey(UUID.randomUUID().toString()), correlation).videoId

    protected fun publishedVideo(): VideoId = newVideo().also {
        videos.completeProcessing(it, 184, correlation)
    }

    protected fun recordsOf(topic: String, videoIds: List<VideoId>): List<KafkaFixture.ConsumedRecord> {
        val keys = videoIds.map { it.value }.toSet()
        return KafkaFixture.readAllRecords(topic).filter { it.key in keys }
    }

    protected fun recordsOf(topic: String, videoId: VideoId) = recordsOf(topic, listOf(videoId))

    protected fun unpublishedCount() = jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL").query(Int::class.java).single()
}

class CrashAfterSending(private val delegate: OutboxMessageSender) : OutboxMessageSender {
    override fun send(message: OutboxMessage): DeliveryReceipt {
        delegate.send(message)
        throw SimulatedCrash()
    }
}

class SimulatedCrash : RuntimeException("caída simulada entre el envío y el UPDATE de published_at")
