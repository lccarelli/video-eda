package dev.videoeda.videos.domain

import java.time.Instant

sealed interface VideoEvent {
    val videoId: VideoId
}

data class VideoProcessingRequested(override val videoId: VideoId, val requestedAt: Instant) : VideoEvent

data class VideoPublished(
    override val videoId: VideoId,
    val creatorId: CreatorId,
    val title: String,
    val durationSeconds: Int,
    val publishedAt: Instant,
) : VideoEvent

data class VideoProcessingFailed(override val videoId: VideoId, val reason: String, val failedAt: Instant) : VideoEvent

data class VideoDeleted(override val videoId: VideoId, val creatorId: CreatorId, val deletedAt: Instant) : VideoEvent
