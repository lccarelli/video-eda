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

class ProcessVideoShareWorkerLockTest : ProcessingWorkerScenario() {
    @Test
    fun `PUB-03 con RENEW un procesamiento mas largo que el lock no se entrega a otro worker`() {
        createProcessingTopicSharedByAllWorkers()
        val calls = AtomicInteger()
        val transcoder = SlowTranscoder(calls, seconds = 7, renewsLock = true)
        startWorker(transcoder)
        startWorker(transcoder)

        val videoId = requestProcessing()

        awaitUntil(description = "el video llega a PUBLISHED") { statusOf(videoId) == "PUBLISHED" }
        assertEquals(1, calls.get(), "el lock de $recordLockMillis ms venció dos veces durante el trabajo y nadie más lo tomó")
        assertEquals(1, videoPublishedCount(videoId))
    }

    @Test
    fun `PUB-03 sin RENEW el lock vence y otro worker lo toma, pero VideoPublished sale una sola vez`() {
        createProcessingTopicSharedByAllWorkers()
        val calls = AtomicInteger()
        val transcoder = SlowTranscoder(calls, seconds = 7, renewsLock = false)
        startWorker(transcoder)
        startWorker(transcoder)

        val videoId = requestProcessing()

        awaitUntil(description = "el segundo worker recibe la re-entrega") { calls.get() >= 2 }
        awaitUntil(description = "el video llega a PUBLISHED") { statusOf(videoId) == "PUBLISHED" }
        waitForTheOtherWorkerToFinish()
        assertEquals(2, calls.get(), "sin RENEW el video se transcodificó dos veces (nota 07)")
        assertEquals(1, videoPublishedCount(videoId), "la idempotencia por estado evita el segundo VideoPublished")
    }
}
