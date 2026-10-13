package videoeda.feed

import com.fasterxml.jackson.databind.ObjectMapper

data class FeedVideoPublished(val videoId: String, val creatorId: String)

class FeedVideoPublishedHandler {
    private val jsonMapper = ObjectMapper()

    fun parseVideoPublished(json: String): FeedVideoPublished {
        val data = jsonMapper.readTree(json).path("data")
        require(data.path("videoId").isTextual) { "falta data.videoId" }
        require(data.path("creatorId").isTextual) { "falta data.creatorId (texto)" }
        return FeedVideoPublished(data.path("videoId").asText(), data.path("creatorId").asText())
    }
}
