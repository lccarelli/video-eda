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

class OutboxPublisherTest : OutboxPublisherScenario() {
    @Test
    fun `un outbox vacio no publica nada`() {
        assertEquals(0, newPublisher().publishPendingEvents())
    }

    @Test
    fun `PUB-01 publica ProcessVideo y VideoPublished con key videoId, cumplen su schema y quedan marcados`() {
        val videoId = publishedVideo()

        val published = newPublisher().publishPendingEvents()

        assertEquals(2, published)
        assertEquals(0, unpublishedCount())
        listOf(Topics.VIDEOS_PROCESSING to "process-video", Topics.VIDEOS_VIDEO to "video-published").forEach { (topic, schema) ->
            val record = recordsOf(topic, videoId).single()
            assertEquals(videoId.value, record.key)
            assertEquals(emptyList<String>(), JsonSchemas.errorsOf(schema, record.value), topic)
            assertEquals(videoId.value, jsonMapper.readTree(record.value)["subject"].asString(), "subject = key")
        }
        assertEquals(0, newPublisher().publishPendingEvents(), "ya no queda nada por publicar")
    }

    @Test
    fun `G1 veinte videos publicados y borrados mantienen VideoPublished antes que VideoDeleted por key`() {
        val videoIds = (1..20).map { publishedVideo() }
        videoIds.forEach { videos.delete(it, lucia, correlation) }

        newPublisher().publishPendingEvents()

        val records = recordsOf(Topics.VIDEOS_VIDEO, videoIds)
        assertEquals(40, records.size)
        videoIds.forEach { videoId ->
            val ofVideo = records.filter { it.key == videoId.value }.sortedWith(compareBy({ it.partition }, { it.offset }))
            assertEquals(1, ofVideo.map { it.partition }.toSet().size, "una key vive en una sola partición")
            assertEquals(listOf(1, 2), ofVideo.map { jsonMapper.readTree(it.value)["sequence"].asInt() })
            assertEquals(
                listOf("dev.videoeda.videos.video-published", "dev.videoeda.videos.video-deleted"),
                ofVideo.map { jsonMapper.readTree(it.value)["type"].asString() },
            )
        }
    }

    @Test
    fun `cada evento publicado deja un log con correlationId, eventId, aggregateId, sequence y posicion en Kafka`() {
        val videoId = publishedVideo()
        val logger = LoggerFactory.getLogger(OutboxPublisher::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also {
            it.start()
            logger.addAppender(it)
        }

        try {
            newPublisher().publishPendingEvents()
        } finally {
            logger.detachAppender(appender)
        }

        val published = appender.list.filter { it.mdcPropertyMap["aggregateId"] == videoId.value }
        assertEquals(2, published.size)
        published.forEach { line ->
            assertEquals(correlation.value, line.mdcPropertyMap["correlationId"])
            assertNotNull(line.mdcPropertyMap["eventId"])
            assertEquals("1", line.mdcPropertyMap["sequence"])
            assertNotNull(line.mdcPropertyMap["topic"])
            assertNotNull(line.mdcPropertyMap["partition"])
            assertNotNull(line.mdcPropertyMap["offset"])
        }
        assertNull(
            appender.list.firstOrNull {
                it.mdcPropertyMap["correlationId"] == null &&
                    it.level.isGreaterOrEqual(ch.qos.logback.classic.Level.WARN)
            },
        )
    }
}
