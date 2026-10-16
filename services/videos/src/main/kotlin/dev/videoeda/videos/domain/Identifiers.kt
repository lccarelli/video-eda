package dev.videoeda.videos.domain

import kotlin.uuid.Uuid

private const val UUID_V7_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"

private fun requireWellFormed(value: String, prefix: String, typeName: String) =
    require(Regex("^${prefix}_$UUID_V7_PATTERN$").matches(value)) {
        "$typeName debe tener el formato ${prefix}_<UUIDv7>: $value"
    }

@JvmInline
value class VideoId(val value: String) {
    init {
        requireWellFormed(value, "vid", "VideoId")
    }

    companion object {
        fun generate() = VideoId("vid_${Uuid.generateV7()}")
    }
}

@JvmInline
value class CreatorId(val value: String) {
    init {
        requireWellFormed(value, "usr", "CreatorId")
    }
}

@JvmInline
value class EventId(val value: String) {
    init {
        requireWellFormed(value, "evt", "EventId")
    }

    companion object {
        fun generate() = EventId("evt_${Uuid.generateV7()}")
    }
}

@JvmInline
value class CorrelationId(val value: String) {
    init {
        requireWellFormed(value, "cor", "CorrelationId")
    }

    companion object {
        fun generate() = CorrelationId("cor_${Uuid.generateV7()}")
    }
}
