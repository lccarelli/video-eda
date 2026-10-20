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

class VideoConcurrencyTest : VideoPersistenceScenario() {
    @Test
    fun `dos workers completan el mismo video a la vez - uno gana y VideoPublished sale una sola vez (20 repeticiones)`() {
        val pool = Executors.newFixedThreadPool(2).also { executors += it }
        repeat(20) { iteration ->
            val videoId = upload(key = java.util.UUID.randomUUID().toString()).videoId
            val barrier = CyclicBarrier(2)
            val attempts = (1..2).map {
                pool.submit<Result<Unit>> {
                    barrier.await(5, TimeUnit.SECONDS)
                    runCatching { videos.completeProcessing(videoId, 184, correlation) }
                }
            }.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, attempts.count { it.isSuccess }, "iteración $iteration: un único ganador")
            assertTrue(
                attempts.single {
                    it.isFailure
                }.exceptionOrNull() is InvalidVideoTransitionException,
                "el perdedor ve el estado ya publicado",
            )
            val publications = outboxRows().filter { it["aggregate_id"] == videoId.value && it["topic"] == Topics.VIDEOS_VIDEO }
            assertEquals(listOf(1), publications.map { it["sequence"] }, "iteración $iteration")
        }
    }

    @Test
    fun `PUB-08 ocho uploads simultaneos con la misma Idempotency-Key crean un solo video`() {
        val writers = 8
        val pool = Executors.newFixedThreadPool(writers).also { executors += it }
        val barrier = CyclicBarrier(writers)

        val results = (1..writers).map {
            pool.submit<Result<VideoId>> {
                barrier.await(5, TimeUnit.SECONDS)
                runCatching { upload().videoId }
            }
        }.map { it.get(15, TimeUnit.SECONDS) }

        assertTrue(results.all { it.isSuccess }, results.mapNotNull { it.exceptionOrNull() }.toString())
        assertEquals(1, results.map { it.getOrThrow() }.toSet().size)
        assertEquals(1, jdbc.sql("SELECT count(*) FROM video").query(Int::class.java).single())
        assertEquals(1, jdbc.sql("SELECT count(*) FROM outbox").query(Int::class.java).single())
    }
}
