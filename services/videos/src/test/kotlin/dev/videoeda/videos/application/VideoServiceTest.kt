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

class VideoServiceTest : VideoServiceScenario() {
    @Test
    fun `PUB-10 un archivo vacio se rechaza y no se escribe nada en el outbox`() {
        assertThrows(EmptyVideoFileException::class.java) { upload(fileSizeBytes = 0) }

        assertTrue(repository.savedVideos.isEmpty())
        assertTrue(outbox.records.isEmpty())
    }

    @Test
    fun `subir un video lo deja en PROCESSING y pide el procesamiento por videos_processing`() {
        val result = upload()

        val video = repository.savedVideo(result.videoId)!!
        assertEquals(VideoStatus.PROCESSING, video.status)
        val request = outbox.recordsOf(Topics.VIDEOS_PROCESSING).single()
        assertEquals(result.videoId, request.entry.aggregateId)
        assertEquals("dev.videoeda.videos.process-video", request.entry.type)
        assertEquals(1, request.sequence)
        assertEquals(publicationCorrelation, request.entry.correlationId)
        assertEquals("storage://uploads/${result.videoId.value}", request.entry.data["storageRef"])
        assertTrue(outbox.recordsOf(Topics.VIDEOS_VIDEO).isEmpty(), "todavía no hay nada publicado")
    }

    @Test
    fun `PUB-08 reenviar el mismo UploadVideo con la misma Idempotency-Key devuelve el mismo resultado y un solo video`() {
        val first = upload()
        val second = upload()

        assertEquals(first, second)
        assertEquals(1, repository.savedVideos.size)
        assertEquals(1, outbox.records.size)
    }

    @Test
    fun `SEC-17 una Idempotency-Key ajena no devuelve el video de otro creator ni crea uno nuevo`() {
        val luciasVideo = upload()

        val failure = assertThrows(IdempotencyKeyInUseException::class.java) {
            videos.upload(mateo, "Gazpacho", 1_048_576, uploadKey, publicationCorrelation)
        }

        assertEquals(1, repository.savedVideos.size, "no se creó un video de Mateo")
        assertTrue(failure.message!!.none { it.isDigit() }, "el mensaje no revela ids: ${failure.message}")
        assertEquals(luciasVideo, upload(), "Lucía sigue recibiendo su propio video con su clave")
    }

    @Test
    fun `PUB-08 b - otra Idempotency-Key crea otro video`() {
        val first = upload()
        val second = upload(key = IdempotencyKey("01a13161-1f9e-7f3a-8498-6164563648ae"))

        assertNotEquals(first.videoId, second.videoId)
        assertEquals(2, repository.savedVideos.size)
    }
}
