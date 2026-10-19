package dev.videoeda.videos.application

import dev.videoeda.videos.domain.CorrelationId
import dev.videoeda.videos.domain.CreatorId
import dev.videoeda.videos.domain.Video
import dev.videoeda.videos.domain.VideoId
import dev.videoeda.videos.domain.VideoStatus
import java.time.Clock

data class UploadVideoResult(val videoId: VideoId, val status: VideoStatus)

class VideoService(
    private val repository: VideoRepository,
    private val outbox: OutboxWriter,
    private val transaction: Transaction,
    private val clock: Clock,
) {
    fun upload(
        creatorId: CreatorId,
        title: String,
        fileSizeBytes: Long,
        idempotencyKey: IdempotencyKey,
        correlationId: CorrelationId,
    ): UploadVideoResult = try {
        transaction.run { uploadOnce(creatorId, title, fileSizeBytes, idempotencyKey, correlationId) }
    } catch (lostRace: DuplicateUploadKeyException) {
        transaction.run { existingUpload(idempotencyKey, creatorId) ?: throw lostRace }
    }

    fun completeProcessing(videoId: VideoId, durationSeconds: Int, correlationId: CorrelationId) {
        transaction.run {
            val video = lockedVideo(videoId)
            val published = video.publish(durationSeconds, clock.instant())
            repository.save(video)
            outbox.appendInCurrentTransaction(OutboxMapping.videoPublished(published, correlationId))
        }
    }

    fun failProcessing(videoId: VideoId, reason: String) {
        transaction.run {
            val video = lockedVideo(videoId)
            video.failProcessing(reason, clock.instant())
            repository.save(video)
        }
    }

    fun delete(videoId: VideoId, requestedBy: CreatorId, correlationId: CorrelationId) {
        transaction.run {
            val video = lockedVideo(videoId)
            if (video.creatorId != requestedBy) throw NotVideoOwnerException(videoId)
            val deleted = video.delete(clock.instant())
            repository.save(video)
            outbox.appendInCurrentTransaction(OutboxMapping.videoDeleted(deleted, correlationId))
        }
    }

    private fun uploadOnce(
        creatorId: CreatorId,
        title: String,
        fileSizeBytes: Long,
        idempotencyKey: IdempotencyKey,
        correlationId: CorrelationId,
    ): UploadVideoResult {
        existingUpload(idempotencyKey, creatorId)?.let { return it }
        val video = Video.upload(creatorId, title, fileSizeBytes)
        val processingRequested = video.startProcessing(clock.instant())
        repository.save(video, idempotencyKey)
        outbox.appendInCurrentTransaction(OutboxMapping.processVideo(processingRequested, correlationId))
        return UploadVideoResult(video.id, video.status)
    }

    private fun existingUpload(idempotencyKey: IdempotencyKey, creatorId: CreatorId): UploadVideoResult? =
        repository.findByUploadKey(idempotencyKey)?.let { existing ->
            if (existing.creatorId != creatorId) throw IdempotencyKeyInUseException(idempotencyKey)
            UploadVideoResult(existing.id, existing.status)
        }

    private fun lockedVideo(videoId: VideoId) = repository.findByIdForUpdate(videoId) ?: throw VideoNotFoundException(videoId)
}
