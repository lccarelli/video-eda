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

class VideoPersistenceTest : VideoPersistenceScenario() {
    @Test
    fun `PUB-10 un archivo vacio no escribe ni en video ni en outbox`() {
        assertThrows(EmptyVideoFileException::class.java) { upload(fileSizeBytes = 0) }

        assertEquals(0, jdbc.sql("SELECT count(*) FROM video").query(Int::class.java).single())
        assertEquals(0, jdbc.sql("SELECT count(*) FROM outbox").query(Int::class.java).single())
    }

    @Test
    fun `subir un video guarda el estado PROCESSING y el ProcessVideo en la misma transaccion`() {
        val result = upload()

        assertEquals("PROCESSING", videoStatusInDatabase(result.videoId))
        val row = outboxRows().single()
        assertEquals(Topics.VIDEOS_PROCESSING, row["topic"])
        assertEquals(result.videoId.value, row["aggregate_id"])
        assertEquals(1, row["sequence"])
        assertNull(row["published_at"], "recién escrito: todavía sin publicar")
    }

    @Test
    fun `PUB-01 completar el procesamiento deja PUBLISHED y VideoPublished en el outbox con sequence 1`() {
        val videoId = upload().videoId

        videos.completeProcessing(videoId, 184, correlation)

        assertEquals("PUBLISHED", videoStatusInDatabase(videoId))
        val published = outboxRows().single { it["topic"] == Topics.VIDEOS_VIDEO }
        assertEquals("dev.videoeda.videos.video-published", published["type"])
        assertEquals(1, published["sequence"])
        assertEquals(correlation.value, published["correlation_id"])
        assertTrue((published["data"] as String).contains("\"durationSeconds\": 184"), published["data"].toString())
    }

    @Test
    fun `el repositorio reconstituye el agregado con el estado guardado`() {
        val videoId = upload().videoId
        videos.completeProcessing(videoId, 184, correlation)

        val video = transaction.run { repository.findByIdForUpdate(videoId) }!!

        assertEquals(VideoStatus.PUBLISHED, video.status)
        assertEquals(lucia, video.creatorId)
        assertEquals("Paella valenciana", video.title)
    }

    @Test
    fun `si falla el outbox el estado del video se revierte`() {
        val videoId = upload().videoId
        val failingOutbox = object : OutboxWriter {
            override fun appendInCurrentTransaction(entry: OutboxEntry) = error("falla simulada al escribir el outbox")
        }
        val videosWithFailingOutbox = VideoService(repository, failingOutbox, transaction, clock)

        assertThrows(IllegalStateException::class.java) {
            videosWithFailingOutbox.completeProcessing(videoId, 184, correlation)
        }

        assertEquals("PROCESSING", videoStatusInDatabase(videoId), "el cambio de estado no debe quedar sin su evento")
        assertEquals(1, outboxRows().size, "solo queda el ProcessVideo de la subida")
    }
}
