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

class ProcessVideoFailureTest : ProcessVideoHandlerScenario() {
    @Test
    fun `PUB-07 una falla transitoria sube al worker, el video sigue en PROCESSING y no se publica nada`() {
        val request = upload()
        val handler = handlerWith(ScriptedVideoTranscoder(mutableListOf({ throw IllegalStateException("timeout del storage") })))

        assertThrows(IllegalStateException::class.java) { handler.handle(request) {} }

        assertEquals(VideoStatus.PROCESSING, repository.savedVideo(request.videoId)!!.status)
        assertTrue(publishedEvents().isEmpty())
    }

    @Test
    fun `PUB-07 tras la falla transitoria, el reintento tiene exito y publica una sola vez`() {
        val request = upload()
        val handler = handlerWith(
            ScriptedVideoTranscoder(mutableListOf({ throw IllegalStateException("timeout del storage") }, { 184 })),
        )
        assertThrows(IllegalStateException::class.java) { handler.handle(request) {} }

        handler.handle(request) {}

        assertEquals(1, publishedEvents().size)
    }

    @Test
    fun `PUB-04 rendirse deja el video FAILED y no publica VideoPublished`() {
        val request = upload()

        handlerWith(ScriptedVideoTranscoder.returning(184)).giveUp(request, "entregas agotadas")

        assertEquals(VideoStatus.FAILED, repository.savedVideo(request.videoId)!!.status)
        assertTrue(publishedEvents().isEmpty())
    }

    @Test
    fun `rendirse con un video ya PUBLISHED no pisa el resultado`() {
        val request = upload()
        val handler = handlerWith(ScriptedVideoTranscoder.returning(184))
        handler.handle(request) {}

        handler.giveUp(request, "llegó tarde")

        assertEquals(VideoStatus.PUBLISHED, repository.savedVideo(request.videoId)!!.status)
        assertEquals(1, publishedEvents().size)
    }

    @Test
    fun `rendirse con un video que no existe no falla`() {
        val unknown = ProcessVideoRequest(EventId.generate(), VideoId.generate(), correlation, "storage://uploads/nada")

        handlerWith(ScriptedVideoTranscoder.returning(184)).giveUp(unknown, "no existe")
    }
}
