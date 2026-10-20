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

class ProcessVideoShareWorkerTest : ProcessingWorkerScenario() {
    @Test
    fun `PUB-01 un ProcessVideo de Kafka deja el video PUBLISHED y emite VideoPublished una vez`() {
        val calls = AtomicInteger()
        startWorker(CountingTranscoder(calls) { 184 })

        val videoId = requestProcessing()

        awaitUntil(description = "el video llega a PUBLISHED") { statusOf(videoId) == "PUBLISHED" }
        assertEquals(1, videoPublishedCount(videoId))
        assertEquals(1, calls.get())
        assertTrue(deadLettersOf(videoId).isEmpty(), "un procesamiento exitoso no deja nada en la DLQ")
    }

    @Test
    fun `dos workers del mismo share group se reparten los videos y cada uno se procesa una sola vez`() {
        val calls = AtomicInteger()
        val transcoder = CountingTranscoder(calls) {
            Thread.sleep(300)
            184
        }
        startWorker(transcoder)
        startWorker(transcoder)

        val videoIds = (1..6).map { requestProcessing("Video $it") }

        awaitUntil(description = "los seis videos llegan a PUBLISHED") { videoIds.all { statusOf(it) == "PUBLISHED" } }
        assertEquals(6, calls.get(), "ni un video procesado dos veces ni uno sin procesar")
        videoIds.forEach { assertEquals(1, videoPublishedCount(it), it.value) }
    }

    @Test
    fun `PUB-07 una falla transitoria se libera, se reintenta y termina publicado sin pasar por la DLQ`() {
        val calls = AtomicInteger()
        startWorker(CountingTranscoder(calls) { call -> if (call == 1) error("timeout del storage") else 184 })

        val videoId = requestProcessing()

        awaitUntil(description = "el video llega a PUBLISHED") { statusOf(videoId) == "PUBLISHED" }
        assertEquals(2, calls.get())
        assertEquals(1, videoPublishedCount(videoId))
        assertTrue(deadLettersOf(videoId).isEmpty())
        assertTrue(alertsLogged().isEmpty())
    }
}
