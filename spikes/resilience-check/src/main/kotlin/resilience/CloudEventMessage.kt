package resilience

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

val jsonMapper = ObjectMapper()

const val COMMENT_POSTED_TYPE = "dev.videoeda.interactions.comment-posted"
const val COMMENT_EDITED_TYPE = "dev.videoeda.interactions.comment-edited"
const val COMMENT_DELETED_TYPE = "dev.videoeda.interactions.comment-deleted"

class InvalidEventException(message: String) : RuntimeException(message)

class UnknownVideoException(val videoId: String) : RuntimeException("video desconocido: $videoId")

class DependencyUnavailableException : RuntimeException("la dependencia no responde")

data class CloudEventMessage(
    val eventId: String,
    val source: String,
    val type: String,
    val streamId: String,
    val sequence: Int,
    val data: JsonNode,
) {
    companion object {
        fun parse(json: String): CloudEventMessage {
            val root = try {
                jsonMapper.readTree(json)
            } catch (failure: Exception) {
                throw InvalidEventException("JSON inválido: ${failure.message}")
            }
            fun requiredText(name: String) =
                root.path(name).takeIf { it.isTextual }?.asText() ?: throw InvalidEventException("falta $name")
            val sequenceNode = root.path("sequence")
            if (!sequenceNode.isInt) throw InvalidEventException("falta sequence")
            return CloudEventMessage(
                eventId = requiredText("id"),
                source = requiredText("source"),
                type = requiredText("type"),
                streamId = requiredText("subject"),
                sequence = sequenceNode.asInt(),
                data = root.path("data"),
            )
        }
    }
}
