package dev.videoeda.videos.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class VideoTest {
    private val creatorId = CreatorId("usr_01a13161-11be-7119-8e3e-bf07f13eb833")
    private val requestedAt = Instant.parse("2026-10-17T12:00:00Z")
    private val publishedAt = Instant.parse("2026-10-17T12:05:00Z")

    private fun uploadedVideo() = Video.upload(creatorId, title = "Paella valenciana", fileSizeBytes = 1_048_576)

    private fun videoIn(status: VideoStatus): Video = uploadedVideo().also { video ->
        when (status) {
            VideoStatus.UPLOADED -> Unit

            VideoStatus.PROCESSING -> video.startProcessing(requestedAt)

            VideoStatus.PUBLISHED -> {
                video.startProcessing(requestedAt)
                video.publish(184, publishedAt)
            }

            VideoStatus.FAILED -> {
                video.startProcessing(requestedAt)
                video.failProcessing("transcodificación fallida", publishedAt)
            }

            VideoStatus.DELETED -> {
                video.startProcessing(requestedAt)
                video.publish(184, publishedAt)
                video.delete(publishedAt)
            }
        }
    }

    @Test
    fun `AGG-04 un video recien subido esta UPLOADED`() {
        assertEquals(VideoStatus.UPLOADED, uploadedVideo().status)
    }

    @Test
    fun `PUB-10 un archivo vacio se rechaza antes de crear el video`() {
        assertThrows(EmptyVideoFileException::class.java) {
            Video.upload(creatorId, title = "Paella valenciana", fileSizeBytes = 0)
        }
    }

    @Test
    fun `AGG-05 camino feliz - UPLOADED, PROCESSING, PUBLISHED y exactamente un VideoPublished`() {
        val video = uploadedVideo()

        val processingRequested = video.startProcessing(requestedAt)
        val published = video.publish(durationSeconds = 184, publishedAt = publishedAt)

        assertEquals(VideoStatus.PUBLISHED, video.status)
        assertEquals(VideoProcessingRequested(video.id, requestedAt), processingRequested)
        assertEquals(VideoPublished(video.id, creatorId, "Paella valenciana", 184, publishedAt), published)
    }

    @Test
    fun `un video puede fallar en el procesamiento y queda FAILED`() {
        val video = videoIn(VideoStatus.PROCESSING)

        val failed = video.failProcessing("transcodificación fallida", publishedAt)

        assertEquals(VideoStatus.FAILED, video.status)
        assertEquals(VideoProcessingFailed(video.id, "transcodificación fallida", publishedAt), failed)
    }

    @Test
    fun `solo un video PUBLISHED puede borrarse`() {
        val video = videoIn(VideoStatus.PUBLISHED)

        val deleted = video.delete(publishedAt)

        assertEquals(VideoStatus.DELETED, video.status)
        assertEquals(VideoDeleted(video.id, creatorId, publishedAt), deleted)
    }

    @Test
    fun `AGG-06 y PUB-02 las transiciones invalidas se rechazan y el estado no cambia`() {
        data class Attempt(val from: VideoStatus, val name: String, val action: (Video) -> Unit)

        val invalidAttempts = listOf(
            Attempt(VideoStatus.UPLOADED, "publicar sin procesar") { it.publish(184, publishedAt) },
            Attempt(VideoStatus.UPLOADED, "borrar sin publicar") { it.delete(publishedAt) },
            Attempt(VideoStatus.PROCESSING, "procesar de nuevo") { it.startProcessing(requestedAt) },
            Attempt(VideoStatus.PROCESSING, "borrar sin publicar") { it.delete(publishedAt) },
            Attempt(VideoStatus.PUBLISHED, "procesar un video publicado") { it.startProcessing(requestedAt) },
            Attempt(VideoStatus.PUBLISHED, "publicar otra vez") { it.publish(184, publishedAt) },
            Attempt(VideoStatus.PUBLISHED, "fallar un video publicado") { it.failProcessing("x", publishedAt) },
            Attempt(VideoStatus.FAILED, "publicar un video FAILED (PUB-02)") { it.publish(184, publishedAt) },
            Attempt(VideoStatus.FAILED, "borrar un video FAILED") { it.delete(publishedAt) },
            Attempt(VideoStatus.DELETED, "publicar un video borrado") { it.publish(184, publishedAt) },
            Attempt(VideoStatus.DELETED, "borrar dos veces") { it.delete(publishedAt) },
        )

        invalidAttempts.forEach { attempt ->
            val video = videoIn(attempt.from)
            assertThrows(InvalidVideoTransitionException::class.java, { attempt.action(video) }, "${attempt.from}: ${attempt.name}")
            assertEquals(attempt.from, video.status, "${attempt.from}: ${attempt.name} no debe cambiar el estado")
        }
    }

    @Test
    fun `el titulo no puede estar vacio`() {
        assertThrows(IllegalArgumentException::class.java) {
            Video.upload(creatorId, title = "  ", fileSizeBytes = 1_048_576)
        }
    }

    @Test
    fun `la duracion publicada debe ser de al menos un segundo`() {
        val video = videoIn(VideoStatus.PROCESSING)

        assertThrows(IllegalArgumentException::class.java) { video.publish(durationSeconds = 0, publishedAt = publishedAt) }
        assertEquals(VideoStatus.PROCESSING, video.status)
    }
}
