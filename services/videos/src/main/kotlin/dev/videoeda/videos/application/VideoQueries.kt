package dev.videoeda.videos.application

import dev.videoeda.videos.domain.VideoId
import dev.videoeda.videos.domain.VideoStatus

data class VideoView(val videoId: VideoId, val creatorId: String, val title: String, val status: VideoStatus)

interface VideoQueries {
    fun findView(videoId: VideoId): VideoView?
}
