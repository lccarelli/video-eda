package dev.videoeda.videos.application

import dev.videoeda.videos.domain.CorrelationId
import dev.videoeda.videos.domain.EventId
import dev.videoeda.videos.domain.InvalidVideoTransitionException
import dev.videoeda.videos.domain.VideoId
import dev.videoeda.videos.domain.VideoStatus

data class ProcessVideoRequest(val eventId: EventId, val videoId: VideoId, val correlationId: CorrelationId, val storageRef: String)

class ProcessVideoRequestHandler(
    private val queries: VideoQueries,
    private val transcoder: VideoTranscoder,
    private val videos: VideoService,
) {
    fun handle(request: ProcessVideoRequest, keepAlive: () -> Unit) {
        val video = queries.findView(request.videoId)
            ?: throw PermanentProcessingException("no existe el video ${request.videoId.value}")
        if (video.status != VideoStatus.PROCESSING) return

        val durationSeconds = transcoder.transcode(request.videoId, video.title, request.storageRef, keepAlive)

        try {
            videos.completeProcessing(request.videoId, durationSeconds, request.correlationId)
        } catch (expectedAlreadyResolved: InvalidVideoTransitionException) {
            return
        }
    }

    fun giveUp(request: ProcessVideoRequest, reason: String) {
        try {
            videos.failProcessing(request.videoId, reason)
        } catch (expectedAlreadyResolved: InvalidVideoTransitionException) {
            return
        } catch (expectedMissing: VideoNotFoundException) {
            return
        }
    }
}
