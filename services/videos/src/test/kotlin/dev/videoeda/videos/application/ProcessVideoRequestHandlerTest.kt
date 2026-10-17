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

class ProcessVideoRequestHandlerTest : ProcessVideoHandlerScenario() {
    @Test
    fun `un ProcessVideo de un video que no existe es una falla permanente y no escribe nada`() {
        val unknown = ProcessVideoRequest(EventId.generate(), VideoId.generate(), correlation, "storage://uploads/nada")
        val transcoder = ScriptedVideoTranscoder.returning(184)

        assertThrows(PermanentProcessingException::class.java) { handlerWith(transcoder).handle(unknown) {} }

        assertEquals(0, transcoder.calls, "sin video no hay nada que transcodificar")
        assertTrue(publishedEvents().isEmpty())
    }

    @Test
    fun `PUB-01 procesar un video lo publica con la duracion transcodificada`() {
        val request = upload()

        handlerWith(ScriptedVideoTranscoder.returning(184)).handle(request) {}

        assertEquals(VideoStatus.PUBLISHED, repository.savedVideo(request.videoId)!!.status)
        val published = publishedEvents().single()
        assertEquals(184, published.entry.data["durationSeconds"])
        assertEquals(correlation, published.entry.correlationId, "el correlationId del upload viaja hasta VideoPublished")
    }

    @Test
    fun `cada video se procesa por separado y publica su propio VideoPublished`() {
        val paella = upload("Paella valenciana")
        val gazpacho = upload("Gazpacho andaluz")
        val handler = handlerWith(ScriptedVideoTranscoder(mutableListOf({ 184 }, { 95 })))

        handler.handle(paella) {}
        handler.handle(gazpacho) {}

        assertEquals(setOf(paella.videoId, gazpacho.videoId), publishedEvents().map { it.entry.aggregateId }.toSet())
        assertEquals(listOf(184, 95), publishedEvents().map { it.entry.data["durationSeconds"] })
    }

    @Test
    fun `PUB-03 el mismo mensaje entregado dos veces publica VideoPublished una sola vez`() {
        val request = upload()
        val transcoder = ScriptedVideoTranscoder(mutableListOf({ 184 }, { error("no debería volver a transcodificar") }))
        val handler = handlerWith(transcoder)

        handler.handle(request) {}
        handler.handle(request) {}

        assertEquals(1, transcoder.calls, "la segunda entrega ni siquiera transcodifica")
        assertEquals(1, publishedEvents().size)
    }

    @Test
    fun `PUB-03 si otro worker termina mientras este transcodifica, su resultado vale y no se publica dos veces`() {
        val request = upload()
        val transcoder = ScriptedVideoTranscoder(
            script = mutableListOf({ 184 }),
            beforeEachCall = {
                videos.completeProcessing(request.videoId, 90, correlation)
            },
        )

        handlerWith(transcoder).handle(request) {}

        assertEquals(1, publishedEvents().size)
        assertEquals(90, publishedEvents().single().entry.data["durationSeconds"], "queda la duración del primero en terminar")
    }
}
