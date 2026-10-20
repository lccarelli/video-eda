package dev.videoeda.videos.infrastructure

import dev.videoeda.videos.application.IdempotencyKey
import dev.videoeda.videos.application.OutboxEntry
import dev.videoeda.videos.application.OutboxWriter
import dev.videoeda.videos.application.Topics
import dev.videoeda.videos.application.VideoService
import dev.videoeda.videos.domain.CorrelationId
import dev.videoeda.videos.domain.CreatorId
import dev.videoeda.videos.domain.EmptyVideoFileException
import dev.videoeda.videos.domain.EventId
import dev.videoeda.videos.domain.InvalidVideoTransitionException
import dev.videoeda.videos.domain.VideoId
import dev.videoeda.videos.domain.VideoStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

abstract class VideoPersistenceScenario {
    protected val lucia = CreatorId("usr_01a13161-11be-7119-8e3e-bf07f13eb833")
    protected val correlation = CorrelationId("cor_01a13161-13c4-7752-b78c-4c2b8c8039fb")
    protected val clock = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC)

    protected val jdbc = PostgresFixture.jdbc
    protected val repository = JdbcVideoRepository(jdbc)
    protected val outbox = JdbcOutboxWriter(jdbc)
    protected val transaction = SpringTransaction(PostgresFixture.transactionManager)

    protected val videos = VideoService(repository, outbox, transaction, clock)
    protected val executors = mutableListOf<java.util.concurrent.ExecutorService>()

    @BeforeEach fun clean() = PostgresFixture.resetTables()

    @AfterEach fun shutdownExecutors() = executors.forEach { it.shutdownNow() }

    protected fun upload(key: String = "01a13161-1f9e-7f3a-8498-6164563648ad", fileSizeBytes: Long = 1_048_576) =
        videos.upload(lucia, "Paella valenciana", fileSizeBytes, IdempotencyKey(key), correlation)

    protected fun outboxRows(): List<Map<String, Any?>> = jdbc.sql(
        "SELECT topic, aggregate_id, type, sequence, correlation_id, published_at, data::text AS data FROM outbox ORDER BY outbox_seq",
    )
        .query().listOfRows()

    protected fun videoStatusInDatabase(videoId: VideoId) = jdbc.sql(
        "SELECT status FROM video WHERE video_id = :id",
    ).param("id", videoId.value).query(String::class.java).optional().orElse(null)
}
