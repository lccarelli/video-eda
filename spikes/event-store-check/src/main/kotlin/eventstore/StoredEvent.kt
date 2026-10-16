package eventstore

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.time.Instant

const val COMMENT_POSTED_TYPE = "dev.videoeda.interactions.comment-posted"
const val COMMENT_EDITED_TYPE = "dev.videoeda.interactions.comment-edited"
const val COMMENT_DELETED_TYPE = "dev.videoeda.interactions.comment-deleted"

val jsonMapper = ObjectMapper()

data class StoredEvent(
    val globalSequence: Long,
    val eventId: String,
    val streamId: String,
    val version: Int,
    val type: String,
    val data: JsonNode,
    val metadata: JsonNode,
    val recordedAt: Instant,
    val publishedAt: Instant?,
)

fun ResultSet.toStoredEvent() = StoredEvent(
    globalSequence = getLong("global_seq"),
    eventId = getString("event_id"),
    streamId = getString("stream_id"),
    version = getInt("version"),
    type = getString("type"),
    data = jsonMapper.readTree(getString("data")),
    metadata = jsonMapper.readTree(getString("metadata")),
    recordedAt = getTimestamp("recorded_at").toInstant(),
    publishedAt = getTimestamp("published_at")?.toInstant(),
)
