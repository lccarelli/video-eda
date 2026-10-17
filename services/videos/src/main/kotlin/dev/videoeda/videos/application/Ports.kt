package dev.videoeda.videos.application

import dev.videoeda.videos.domain.CorrelationId
import dev.videoeda.videos.domain.EventId
import dev.videoeda.videos.domain.Video
import dev.videoeda.videos.domain.VideoId
import java.time.Instant
import java.util.UUID

object Topics {
    const val VIDEOS_VIDEO = "videos.video"
    const val VIDEOS_PROCESSING = "videos.processing"
    const val VIDEOS_PROCESSING_DLQ = "videos.processing.dlq"
}

class PermanentProcessingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

interface VideoTranscoder {
    fun transcode(videoId: VideoId, title: String, storageRef: String, keepAlive: () -> Unit): Int
}

@JvmInline
value class IdempotencyKey(val value: String) {
    init {
        UUID.fromString(value)
    }
}

class NotVideoOwnerException(val videoId: VideoId) : RuntimeException("el video ${videoId.value} pertenece a otro creator")

class IdempotencyKeyInUseException(val uploadKey: IdempotencyKey) : RuntimeException("la Idempotency-Key ya está en uso por otro creator")

class VideoNotFoundException(val videoId: VideoId) : RuntimeException("no existe el video ${videoId.value}")

class DuplicateUploadKeyException(val uploadKey: IdempotencyKey) :
    RuntimeException("ya existe un video para la Idempotency-Key ${uploadKey.value}")

data class OutboxEntry(
    val eventId: EventId,
    val topic: String,
    val aggregateId: VideoId,
    val type: String,
    val occurredAt: Instant,
    val correlationId: CorrelationId,
    val data: Map<String, Any?>,
)

interface OutboxWriter {
    fun appendInCurrentTransaction(entry: OutboxEntry)
}

interface VideoRepository {
    fun save(video: Video, uploadKey: IdempotencyKey? = null)

    fun findByIdForUpdate(videoId: VideoId): Video?
    fun findByUploadKey(uploadKey: IdempotencyKey): Video?
}

interface Transaction {
    fun <T> run(block: () -> T): T
}
