package dev.videoeda.videos.application

import dev.videoeda.videos.domain.CorrelationId
import dev.videoeda.videos.domain.CreatorId
import dev.videoeda.videos.domain.EmptyVideoFileException
import dev.videoeda.videos.domain.InvalidVideoTransitionException
import dev.videoeda.videos.domain.VideoId
import dev.videoeda.videos.domain.VideoStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

abstract class VideoServiceScenario {
    protected val lucia = CreatorId("usr_01a13161-11be-7119-8e3e-bf07f13eb833")
    protected val mateo = CreatorId("usr_01a13161-11e3-764d-8aee-4eb50cdb9329")
    protected val publicationCorrelation = CorrelationId("cor_01a13161-13c4-7752-b78c-4c2b8c8039fb")
    protected val uploadKey = IdempotencyKey("01a13161-1f9e-7f3a-8498-6164563648ad")
    protected val now = Instant.parse("2026-10-17T12:00:00Z")
    protected val clock = Clock.fixed(now, ZoneOffset.UTC)

    protected val repository = InMemoryVideoRepository()
    protected val outbox = InMemoryOutbox()
    protected val transaction = DirectTransaction()

    protected val videos = VideoService(repository, outbox, transaction, clock)

    protected fun upload(key: IdempotencyKey = uploadKey, fileSizeBytes: Long = 1_048_576) =
        videos.upload(lucia, "Paella valenciana", fileSizeBytes, key, publicationCorrelation)
}
