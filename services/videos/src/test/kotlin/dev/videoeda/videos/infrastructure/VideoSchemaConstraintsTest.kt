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

class VideoSchemaConstraintsTest : VideoPersistenceScenario() {
    @Test
    fun `la sequence es el ordinal contiguo dentro de cada stream (agregado, topic)`() {
        val first = upload().videoId
        val second = upload(key = "01a13161-1f9e-7f3a-8498-6164563648ae").videoId
        videos.completeProcessing(second, 60, correlation)
        videos.completeProcessing(first, 184, correlation)
        videos.delete(first, lucia, correlation)

        fun sequencesOf(videoId: VideoId, topic: String) =
            outboxRows().filter { it["aggregate_id"] == videoId.value && it["topic"] == topic }.map { it["sequence"] }

        assertEquals(listOf(1), sequencesOf(first, Topics.VIDEOS_PROCESSING))
        assertEquals(listOf(1, 2), sequencesOf(first, Topics.VIDEOS_VIDEO))
        assertEquals(listOf(1), sequencesOf(second, Topics.VIDEOS_VIDEO))
    }

    @Test
    fun `UNIQUE sobre (aggregate_id, topic, sequence) es la red de seguridad contra un ordinal repetido`() {
        val videoId = upload().videoId
        fun insertDuplicate() = jdbc.sql(
            """
            INSERT INTO outbox (event_id, topic, aggregate_id, type, occurred_at, correlation_id, sequence, data)
            VALUES (:eventId, 'videos.processing', :aggregateId, 'x', now(), :correlationId, 1, '{}'::jsonb)
            """.trimIndent(),
        ).param("eventId", EventId.generate().value).param("aggregateId", videoId.value).param("correlationId", correlation.value).update()

        assertThrows(DuplicateKeyException::class.java) { insertDuplicate() }
    }

    @Test
    fun `los CHECK rechazan ids con formato equivocado`() {
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.sql("INSERT INTO video (video_id, creator_id, title, status) VALUES ('v-1', :creator, 'x', 'UPLOADED')")
                .param("creator", lucia.value).update()
        }
    }
}
