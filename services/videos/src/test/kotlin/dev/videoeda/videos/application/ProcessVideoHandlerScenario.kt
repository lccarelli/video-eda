package dev.videoeda.videos.application

import dev.videoeda.videos.domain.CorrelationId
import dev.videoeda.videos.domain.CreatorId
import dev.videoeda.videos.domain.EventId
import dev.videoeda.videos.domain.VideoId
import dev.videoeda.videos.domain.VideoStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

abstract class ProcessVideoHandlerScenario {
    protected val lucia = CreatorId("usr_01a13161-11be-7119-8e3e-bf07f13eb833")
    protected val correlation = CorrelationId("cor_01a13161-13c4-7752-b78c-4c2b8c8039fb")
    protected val clock = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC)

    protected val repository = InMemoryVideoRepository()
    protected val outbox = InMemoryOutbox()
    protected val transaction = DirectTransaction()

    protected val videos = VideoService(repository, outbox, transaction, clock)

    protected fun upload(title: String = "Paella valenciana"): ProcessVideoRequest {
        val key = IdempotencyKey(UUID.randomUUID().toString())
        val videoId = videos.upload(lucia, title, 1_048_576, key, correlation).videoId
        return ProcessVideoRequest(EventId.generate(), videoId, correlation, "storage://uploads/${videoId.value}")
    }

    protected fun handlerWith(transcoder: VideoTranscoder) = ProcessVideoRequestHandler(
        InMemoryVideoQueries(repository),
        transcoder,
        videos,
    )

    protected fun publishedEvents() = outbox.recordsOf(Topics.VIDEOS_VIDEO)
}
