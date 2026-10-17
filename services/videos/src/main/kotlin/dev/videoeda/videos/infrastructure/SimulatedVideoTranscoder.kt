package dev.videoeda.videos.infrastructure

import dev.videoeda.videos.application.PermanentProcessingException
import dev.videoeda.videos.application.VideoTranscoder
import dev.videoeda.videos.domain.VideoId
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class SimulatedVideoTranscoder(
    private val processingTime: Duration,
    private val keepAliveInterval: Duration,
    private val durationSeconds: Int = 184,
) : VideoTranscoder {
    private val videosAlreadyFailedOnce = ConcurrentHashMap.newKeySet<VideoId>()

    override fun transcode(videoId: VideoId, title: String, storageRef: String, keepAlive: () -> Unit): Int {
        work(keepAlive)
        if (PERMANENT_FAILURE_MARK in title) {
            throw PermanentProcessingException("archivo corrupto (simulado por la marca $PERMANENT_FAILURE_MARK)")
        }
        if (TRANSIENT_FAILURE_MARK in title && videosAlreadyFailedOnce.add(videoId)) {
            error("timeout del storage (simulado por la marca $TRANSIENT_FAILURE_MARK)")
        }
        return durationSeconds
    }

    private fun work(keepAlive: () -> Unit) {
        var remaining = processingTime
        while (!remaining.isZero && !remaining.isNegative) {
            val slice = minOf(remaining, keepAliveInterval)
            Thread.sleep(slice)
            keepAlive()
            remaining = remaining.minus(slice)
        }
    }

    companion object {
        const val TRANSIENT_FAILURE_MARK = "[falla-transitoria]"
        const val PERMANENT_FAILURE_MARK = "[falla-permanente]"
    }
}
