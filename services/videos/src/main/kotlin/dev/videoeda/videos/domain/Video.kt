package dev.videoeda.videos.domain

import java.time.Instant

class Video private constructor(val id: VideoId, val creatorId: CreatorId, val title: String, status: VideoStatus) {
    var status: VideoStatus = status
        private set

    fun startProcessing(requestedAt: Instant): VideoProcessingRequested {
        requireStatus(VideoStatus.UPLOADED, "procesar")
        status = VideoStatus.PROCESSING
        return VideoProcessingRequested(id, requestedAt)
    }

    fun publish(durationSeconds: Int, publishedAt: Instant): VideoPublished {
        requireStatus(VideoStatus.PROCESSING, "publicar")
        require(durationSeconds >= 1) { "la duración publicada debe ser de al menos 1 segundo" }
        status = VideoStatus.PUBLISHED
        return VideoPublished(id, creatorId, title, durationSeconds, publishedAt)
    }

    fun failProcessing(reason: String, failedAt: Instant): VideoProcessingFailed {
        requireStatus(VideoStatus.PROCESSING, "marcar como fallido")
        status = VideoStatus.FAILED
        return VideoProcessingFailed(id, reason, failedAt)
    }

    fun delete(deletedAt: Instant): VideoDeleted {
        requireStatus(VideoStatus.PUBLISHED, "borrar")
        status = VideoStatus.DELETED
        return VideoDeleted(id, creatorId, deletedAt)
    }

    private fun requireStatus(expected: VideoStatus, attempted: String) {
        if (status != expected) throw InvalidVideoTransitionException(id, status, attempted)
    }

    companion object {
        fun reconstitute(id: VideoId, creatorId: CreatorId, title: String, status: VideoStatus) = Video(id, creatorId, title, status)

        fun upload(creatorId: CreatorId, title: String, fileSizeBytes: Long): Video {
            if (fileSizeBytes <= 0) throw EmptyVideoFileException()
            require(title.isNotBlank()) { "el título no puede estar vacío" }
            return Video(VideoId.generate(), creatorId, title, VideoStatus.UPLOADED)
        }
    }
}
