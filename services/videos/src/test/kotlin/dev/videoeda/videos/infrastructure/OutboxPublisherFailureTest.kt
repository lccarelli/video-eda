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

class OutboxPublisherFailureTest : OutboxPublisherScenario() {
    @Test
    fun `PUB-05 y PUB-06 una caida entre el envio y el marcado reenvia el mismo event_id y no pierde el evento`() {
        val videoId = publishedVideo()
        val crashingSender = CrashAfterSending(KafkaOutboxMessageSender(KafkaFixture.newProducer().also { producers += it }))

        assertThrows(SimulatedCrash::class.java) { newPublisher(crashingSender).publishPendingEvents() }
        assertEquals(2, unpublishedCount(), "el proceso murió antes de marcar: las filas siguen pendientes")
        assertEquals(1, recordsOf(Topics.VIDEOS_PROCESSING, videoId).size, "pero el broker ya tiene el primer evento")

        assertEquals(2, newPublisher().publishPendingEvents(), "el servicio se recupera y publica lo pendiente")

        val processing = recordsOf(Topics.VIDEOS_PROCESSING, videoId)
        assertEquals(2, processing.size, "ProcessVideo salió dos veces")
        assertEquals(1, processing.map { jsonMapper.readTree(it.value)["id"].asString() }.toSet().size, "con el mismo id")
        assertEquals(1, recordsOf(Topics.VIDEOS_VIDEO, videoId).size, "VideoPublished salió una vez")
        assertEquals(0, unpublishedCount())
    }

    @Test
    fun `con Kafka caido el publisher no marca nada y al volver publica en orden`() {
        val videoId = publishedVideo()
        videos.delete(videoId, lucia, correlation)
        val unreachableProducer = KafkaFixture.newProducer(
            extraConfig = mapOf("max.block.ms" to 1_500, "delivery.timeout.ms" to 2_000, "request.timeout.ms" to 1_000, "linger.ms" to 0),
            bootstrap = "localhost:1",
        ).also { producers += it }

        val publishedWhileDown = newPublisher(KafkaOutboxMessageSender(unreachableProducer)).publishPendingEvents()

        assertEquals(0, publishedWhileDown)
        assertEquals(3, unpublishedCount(), "ProcessVideo, VideoPublished y VideoDeleted siguen pendientes")

        assertEquals(3, newPublisher().publishPendingEvents())
        val sequences = recordsOf(Topics.VIDEOS_VIDEO, videoId).sortedWith(compareBy({ it.partition }, { it.offset }))
            .map { jsonMapper.readTree(it.value)["sequence"].asInt() }
        assertEquals(listOf(1, 2), sequences)
    }

    @Test
    fun `dos instancias a la vez - la segunda no obtiene el lock y no hay duplicados`() {
        val videoIds = (1..5).map { publishedVideo() }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockingSender = object : OutboxMessageSender {
            private val delegate = KafkaOutboxMessageSender(KafkaFixture.newProducer().also { producers += it })
            override fun send(message: OutboxMessage): DeliveryReceipt {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                return delegate.send(message)
            }
        }
        val pool = Executors.newFixedThreadPool(1).also { executors += it }
        val firstInstance = pool.submit<Int> { newPublisher(blockingSender).publishPendingEvents() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))

        val secondInstance = newPublisher().publishPendingEvents()
        release.countDown()
        val firstInstancePublished = firstInstance.get(30, TimeUnit.SECONDS)

        assertEquals(0, secondInstance, "la segunda instancia no obtuvo el lock")
        assertEquals(10, firstInstancePublished)
        val total = recordsOf(Topics.VIDEOS_VIDEO, videoIds).size + recordsOf(Topics.VIDEOS_PROCESSING, videoIds).size
        assertEquals(10, total, "cada evento salió una sola vez")
    }
}
