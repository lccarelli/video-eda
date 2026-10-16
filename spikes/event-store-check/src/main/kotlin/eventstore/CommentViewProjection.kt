package eventstore

import java.sql.Connection
import java.sql.Timestamp

object CommentViewProjection {
    fun project(connection: Connection, event: StoredEvent) {
        when (event.type) {
            COMMENT_POSTED_TYPE -> insertActiveComment(connection, event)
            COMMENT_EDITED_TYPE -> updateCommentText(connection, event)
            COMMENT_DELETED_TYPE -> markCommentAsDeleted(connection, event)
            else -> error("tipo desconocido: ${event.type}")
        }
    }

    private fun insertActiveComment(connection: Connection, event: StoredEvent) {
        connection.prepareStatement(
            "INSERT INTO comment_view (comment_id, video_id, author_id, text, status, version, posted_at, updated_at) " +
                "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?::timestamptz, ?)",
        ).use { statement ->
            statement.setString(1, event.streamId)
            statement.setString(2, event.data["videoId"].asText())
            statement.setString(3, event.data["authorId"].asText())
            statement.setString(4, event.data["text"].asText())
            statement.setInt(5, event.version)
            statement.setString(6, event.data["postedAt"].asText())
            statement.setTimestamp(7, Timestamp.from(event.recordedAt))
            statement.executeUpdate()
        }
    }

    private fun updateCommentText(connection: Connection, event: StoredEvent) {
        connection.prepareStatement(
            "UPDATE comment_view SET text = ?, version = ?, updated_at = ? WHERE comment_id = ?",
        ).use { statement ->
            statement.setString(1, event.data["text"].asText())
            statement.setInt(2, event.version)
            statement.setTimestamp(3, Timestamp.from(event.recordedAt))
            statement.setString(4, event.streamId)
            statement.executeUpdate()
        }
    }

    private fun markCommentAsDeleted(connection: Connection, event: StoredEvent) {
        connection.prepareStatement(
            "UPDATE comment_view SET status = 'DELETED', version = ?, updated_at = ? WHERE comment_id = ?",
        ).use { statement ->
            statement.setInt(1, event.version)
            statement.setTimestamp(2, Timestamp.from(event.recordedAt))
            statement.setString(3, event.streamId)
            statement.executeUpdate()
        }
    }
}
