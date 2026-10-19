package dev.videoeda.videos.infrastructure

import dev.videoeda.videos.application.PermanentProcessingException
import dev.videoeda.videos.application.ProcessVideoRequest
import dev.videoeda.videos.domain.CorrelationId
import dev.videoeda.videos.domain.EventId
import dev.videoeda.videos.domain.VideoId
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

private const val PROCESS_VIDEO_TYPE = "dev.videoeda.videos.process-video"
private val jsonMapper = JsonMapper.builder().build()

fun parseProcessVideo(cloudEventJson: String): ProcessVideoRequest = try {
    val event = jsonMapper.readTree(cloudEventJson)
    require(event.text("type") == PROCESS_VIDEO_TYPE) { "type inesperado: ${event.text("type")}" }
    val data = event.get("data")
    ProcessVideoRequest(
        eventId = EventId(event.text("id")),
        videoId = VideoId(data.text("videoId")),
        correlationId = CorrelationId(event.text("correlationid")),
        storageRef = data.text("storageRef"),
    )
} catch (expectedInvalid: Exception) {
    throw PermanentProcessingException(expectedInvalid.message ?: "mensaje inválido", expectedInvalid)
}

private fun JsonNode?.text(field: String): String =
    this?.get(field)?.takeIf { it.isString }?.asString() ?: throw IllegalArgumentException("falta $field")
