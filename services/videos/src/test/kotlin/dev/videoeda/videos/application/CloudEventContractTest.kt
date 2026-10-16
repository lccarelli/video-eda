package dev.videoeda.videos.application

import com.fasterxml.jackson.databind.ObjectMapper
import dev.videoeda.videos.JsonSchemas
import dev.videoeda.videos.domain.CorrelationId
import dev.videoeda.videos.domain.CreatorId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CloudEventContractTest {
    private val mapper = ObjectMapper()

    private val lucia = CreatorId("usr_01a13161-11be-7119-8e3e-bf07f13eb833")
    private val correlation = CorrelationId("cor_01a13161-13c4-7752-b78c-4c2b8c8039fb")
    private val clock = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC)
    private val repository = InMemoryVideoRepository()
    private val outbox = InMemoryOutbox()
    private val videos = VideoService(repository, outbox, DirectTransaction(), clock)

    private fun errorsAgainst(schemaName: String, record: OutboxRecord): List<String> =
        JsonSchemas.errorsOf(schemaName, mapper.writeValueAsString(CloudEvents.toMap(record.entry, record.sequence)))

    @Test
    fun `ProcessVideo, VideoPublished y VideoDeleted cumplen su JSON Schema`() {
        val videoId = uploadPaella()
        videos.completeProcessing(videoId, 184, correlation)
        videos.delete(videoId, lucia, correlation)

        val schemaByType = mapOf(
            "dev.videoeda.videos.process-video" to "process-video",
            "dev.videoeda.videos.video-published" to "video-published",
            "dev.videoeda.videos.video-deleted" to "video-deleted",
        )
        assertEquals(3, outbox.records.size)
        outbox.records.forEach { record ->
            assertEquals(emptyList<String>(), errorsAgainst(schemaByType.getValue(record.entry.type), record), record.entry.type)
        }
    }

    @Test
    fun `un cambio interno del agregado no cambia el payload (AGG-02) - el mapeo es explicito`() {
        videos.completeProcessing(uploadPaella(), 184, correlation)

        val publishedData = outbox.recordsOf(Topics.VIDEOS_VIDEO).single().entry.data

        assertEquals(setOf("videoId", "creatorId", "title", "durationSeconds", "publishedAt"), publishedData.keys)
    }

    private fun uploadPaella() = videos.upload(
        lucia,
        "Paella valenciana",
        1_048_576,
        IdempotencyKey("01a13161-1f9e-7f3a-8498-6164563648ad"),
        correlation,
    ).videoId
}
