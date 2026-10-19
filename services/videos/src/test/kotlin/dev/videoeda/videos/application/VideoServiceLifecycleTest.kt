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

class VideoServiceLifecycleTest : VideoServiceScenario() {
    @Test
    fun `PUB-01 completar el procesamiento publica VideoPublished con key videoId y sequence 1`() {
        val videoId = upload().videoId

        videos.completeProcessing(videoId, durationSeconds = 184, publicationCorrelation)

        assertEquals(VideoStatus.PUBLISHED, repository.savedVideo(videoId)!!.status)
        val published = outbox.recordsOf(Topics.VIDEOS_VIDEO).single()
        assertEquals("dev.videoeda.videos.video-published", published.entry.type)
        assertEquals(videoId, published.entry.aggregateId)
        assertEquals(1, published.sequence)
        assertEquals(publicationCorrelation, published.entry.correlationId)
        assertEquals(
            mapOf(
                "videoId" to videoId.value,
                "creatorId" to lucia.value,
                "title" to "Paella valenciana",
                "durationSeconds" to 184,
                "publishedAt" to now.toString(),
            ),
            published.entry.data,
        )
    }

    @Test
    fun `PUB-04 un procesamiento que falla deja el video FAILED y no publica nada en videos_video`() {
        val videoId = upload().videoId

        videos.failProcessing(videoId, "transcodificación fallida")

        assertEquals(VideoStatus.FAILED, repository.savedVideo(videoId)!!.status)
        assertTrue(outbox.recordsOf(Topics.VIDEOS_VIDEO).isEmpty())
    }

    @Test
    fun `SEC-10 otro creator no puede borrar un video ajeno y no se publica VideoDeleted`() {
        val videoId = upload().videoId
        videos.completeProcessing(videoId, 184, publicationCorrelation)

        assertThrows(NotVideoOwnerException::class.java) {
            videos.delete(videoId, mateo, publicationCorrelation)
        }

        assertEquals(VideoStatus.PUBLISHED, repository.savedVideo(videoId)!!.status)
        assertTrue(outbox.recordsOf(Topics.VIDEOS_VIDEO).none { it.entry.type == "dev.videoeda.videos.video-deleted" })
    }

    @Test
    fun `PUB-02 completar un video FAILED se rechaza y no se publica nada`() {
        val videoId = upload().videoId
        videos.failProcessing(videoId, "transcodificación fallida")

        assertThrows(InvalidVideoTransitionException::class.java) {
            videos.completeProcessing(videoId, 184, publicationCorrelation)
        }

        assertEquals(VideoStatus.FAILED, repository.savedVideo(videoId)!!.status)
        assertTrue(outbox.recordsOf(Topics.VIDEOS_VIDEO).isEmpty())
    }

    @Test
    fun `borrar un video publicado emite VideoDeleted con sequence 2 en el mismo stream que VideoPublished`() {
        val videoId = upload().videoId
        videos.completeProcessing(videoId, 184, publicationCorrelation)

        videos.delete(videoId, lucia, publicationCorrelation)

        assertEquals(VideoStatus.DELETED, repository.savedVideo(videoId)!!.status)
        val streamOfVideo = outbox.recordsOf(Topics.VIDEOS_VIDEO).filter { it.entry.aggregateId == videoId }
        assertEquals(listOf(1, 2), streamOfVideo.map { it.sequence }, "el ordinal es contiguo dentro del stream publicado")
        assertEquals("dev.videoeda.videos.video-deleted", streamOfVideo.last().entry.type)
    }

    @Test
    fun `la sequence se cuenta por agregado y no entre videos distintos`() {
        val firstVideo = upload().videoId
        val secondVideo = upload(key = IdempotencyKey("01a13161-1f9e-7f3a-8498-6164563648ae")).videoId

        videos.completeProcessing(secondVideo, 60, publicationCorrelation)
        videos.completeProcessing(firstVideo, 184, publicationCorrelation)

        assertEquals(listOf(1, 1), outbox.recordsOf(Topics.VIDEOS_VIDEO).map { it.sequence })
    }

    @Test
    fun `operar sobre un video inexistente se rechaza`() {
        val unknown = VideoId("vid_01a13161-1208-73ad-b8df-5e1e8e69b948")

        assertThrows(VideoNotFoundException::class.java) {
            videos.completeProcessing(unknown, 184, publicationCorrelation)
        }
        assertTrue(outbox.records.isEmpty())
    }
}
