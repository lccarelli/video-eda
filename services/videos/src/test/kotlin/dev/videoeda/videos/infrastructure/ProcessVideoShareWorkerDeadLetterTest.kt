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

class ProcessVideoShareWorkerDeadLetterTest : ProcessingWorkerScenario() {
    @Test
    fun `PUB-04 una falla en cada entrega agota las tres, va a la DLQ con su causa, deja FAILED y dispara una alerta`() {
        val calls = AtomicInteger()
        startWorker(CountingTranscoder(calls) { error("timeout del storage") })

        val videoId = requestProcessing()

        awaitUntil(description = "el video llega a FAILED") { statusOf(videoId) == "FAILED" }
        val deadLetter = KafkaFixture.awaitRecordsWithKey(deadLetterTopic, videoId.value).single()
        assertEquals("3", deadLetter.headers[KafkaDeadLetterPublisher.ATTEMPTS_HEADER])
        assertTrue(deadLetter.headers.getValue(KafkaDeadLetterPublisher.CAUSE_HEADER).contains("entregas agotadas"))
        assertEquals(processingTopic, deadLetter.headers[KafkaDeadLetterPublisher.ORIGIN_TOPIC_HEADER])
        assertTrue(deadLetter.headers.getValue(KafkaDeadLetterPublisher.EVENT_ID_HEADER).startsWith("evt_"))
        assertTrue(deadLetter.value.contains(videoId.value), "la DLQ conserva el mensaje original")
        assertEquals(3, calls.get())
        assertEquals(0, videoPublishedCount(videoId), "no se emite VideoPublished")
        awaitUntil(description = "se registra la alerta") { alertsLogged().isNotEmpty() }
        assertEquals(videoId.value, alertsLogged().first().mdcPropertyMap["aggregateId"])
    }

    @Test
    fun `una falla permanente va directo a la DLQ en la primera entrega`() {
        val calls = AtomicInteger()
        startWorker(CountingTranscoder(calls) { throw PermanentProcessingException("archivo corrupto") })

        val videoId = requestProcessing()

        awaitUntil(description = "el video llega a FAILED") { statusOf(videoId) == "FAILED" }
        val deadLetter = KafkaFixture.awaitRecordsWithKey(deadLetterTopic, videoId.value).single()
        assertEquals("1", deadLetter.headers[KafkaDeadLetterPublisher.ATTEMPTS_HEADER])
        assertTrue(deadLetter.headers.getValue(KafkaDeadLetterPublisher.CAUSE_HEADER).contains("archivo corrupto"))
        assertEquals(1, calls.get(), "una falla permanente no se reintenta")
    }

    @Test
    fun `un mensaje que no cumple el contrato va a la DLQ y no bloquea al siguiente`() {
        val calls = AtomicInteger()
        startWorker(CountingTranscoder(calls) { 184 })
        newProducer().send(ProducerRecord(processingTopic, "vid_basura", "esto no es JSON")).get()

        val videoId = requestProcessing()

        awaitUntil(description = "el video válido llega a PUBLISHED") { statusOf(videoId) == "PUBLISHED" }
        val poison = KafkaFixture.awaitRecordsWithKey(deadLetterTopic, "vid_basura").single()
        assertTrue(poison.headers.getValue(KafkaDeadLetterPublisher.CAUSE_HEADER).startsWith("mensaje inválido"))
        assertEquals("esto no es JSON", poison.value)
        assertEquals(1, calls.get(), "solo el mensaje válido llegó al transcoder")
    }
}
