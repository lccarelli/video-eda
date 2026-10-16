package dev.videoeda.videos.application

import dev.videoeda.videos.domain.CorrelationId
import dev.videoeda.videos.domain.EventId
import dev.videoeda.videos.domain.VideoDeleted
import dev.videoeda.videos.domain.VideoProcessingRequested
import dev.videoeda.videos.domain.VideoPublished

object OutboxMapping {
    fun processVideo(event: VideoProcessingRequested, correlationId: CorrelationId) = OutboxEntry(
        eventId = EventId.generate(),
        topic = Topics.VIDEOS_PROCESSING,
        aggregateId = event.videoId,
        type = "dev.videoeda.videos.process-video",
        occurredAt = event.requestedAt,
        correlationId = correlationId,
        data = mapOf(
            "videoId" to event.videoId.value,
            "storageRef" to "storage://uploads/${event.videoId.value}",
            "requestedAt" to event.requestedAt.toString(),
        ),
    )

    fun videoPublished(event: VideoPublished, correlationId: CorrelationId) = OutboxEntry(
        eventId = EventId.generate(),
        topic = Topics.VIDEOS_VIDEO,
        aggregateId = event.videoId,
        type = "dev.videoeda.videos.video-published",
        occurredAt = event.publishedAt,
        correlationId = correlationId,
        data = mapOf(
            "videoId" to event.videoId.value,
            "creatorId" to event.creatorId.value,
            "title" to event.title,
            "durationSeconds" to event.durationSeconds,
            "publishedAt" to event.publishedAt.toString(),
        ),
    )

    fun videoDeleted(event: VideoDeleted, correlationId: CorrelationId) = OutboxEntry(
        eventId = EventId.generate(),
        topic = Topics.VIDEOS_VIDEO,
        aggregateId = event.videoId,
        type = "dev.videoeda.videos.video-deleted",
        occurredAt = event.deletedAt,
        correlationId = correlationId,
        data = mapOf(
            "videoId" to event.videoId.value,
            "creatorId" to event.creatorId.value,
            "deletedAt" to event.deletedAt.toString(),
        ),
    )
}

object CloudEvents {
    fun toMap(entry: OutboxEntry, sequence: Int): Map<String, Any?> = linkedMapOf(
        "specversion" to "1.0",
        "id" to entry.eventId.value,
        "source" to "/videos",
        "type" to entry.type,
        "subject" to entry.aggregateId.value,
        "time" to entry.occurredAt.toString(),
        "datacontenttype" to "application/json",
        "correlationid" to entry.correlationId.value,
        "sequence" to sequence,
        "data" to entry.data,
    )
}
