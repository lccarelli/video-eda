package videoeda.notifications

import com.fasterxml.jackson.databind.ObjectMapper

data class NotificationsVideoPublished(val videoId: String, val creatorId: String, val title: String)

class NotificationsVideoPublishedHandler {
    private val jsonMapper = ObjectMapper()

    fun parseVideoPublished(json: String): NotificationsVideoPublished {
        val data = jsonMapper.readTree(json).path("data")
        require(data.path("videoId").isTextual) { "falta data.videoId" }
        require(data.path("creatorId").isTextual) { "falta data.creatorId (texto)" }
        require(data.path("title").isTextual) { "falta data.title" }
        return NotificationsVideoPublished(data.path("videoId").asText(), data.path("creatorId").asText(), data.path("title").asText())
    }
}
