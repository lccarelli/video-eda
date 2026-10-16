package resilience

import java.sql.Connection
import javax.sql.DataSource

enum class SequenceGuard {
    NONE,

    DISCARD_OLD_ONLY,

    STRICT,
}

enum class HandleOutcome { APPLIED, DISCARDED_DUPLICATE, DISCARDED_OLD, SEQUENCE_GAP }

class CommentCopyHandler(
    private val dataSource: DataSource,
    private val sequenceGuard: SequenceGuard = SequenceGuard.STRICT,

    private val isDependencyAvailable: () -> Boolean = { true },
) {
    fun handle(event: CloudEventMessage): HandleOutcome {
        if (!isDependencyAvailable()) throw DependencyUnavailableException()
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val outcome = decideAndApply(connection, event)
                if (outcome == HandleOutcome.APPLIED) connection.commit() else connection.rollback()
                outcome
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    private fun decideAndApply(connection: Connection, event: CloudEventMessage): HandleOutcome {
        if (!insertIntoInbox(connection, event)) return HandleOutcome.DISCARDED_DUPLICATE
        val lastSequence = readLastSequence(connection, event.streamId)
        when (sequenceGuard) {
            SequenceGuard.NONE -> Unit
            SequenceGuard.DISCARD_OLD_ONLY -> if (event.sequence <= lastSequence) return HandleOutcome.DISCARDED_OLD
            SequenceGuard.STRICT -> when {
                event.sequence <= lastSequence -> return HandleOutcome.DISCARDED_OLD
                event.sequence > lastSequence + 1 -> return HandleOutcome.SEQUENCE_GAP
            }
        }
        applyEffect(connection, event)
        saveLastSequence(connection, event.streamId, event.sequence)
        return HandleOutcome.APPLIED
    }

    private fun insertIntoInbox(connection: Connection, event: CloudEventMessage): Boolean =
        connection.prepareStatement("INSERT INTO inbox (source, id) VALUES (?, ?) ON CONFLICT DO NOTHING").use { statement ->
            statement.setString(1, event.source)
            statement.setString(2, event.eventId)
            statement.executeUpdate() == 1
        }

    private fun readLastSequence(connection: Connection, streamId: String): Int =
        connection.prepareStatement("SELECT last_sequence FROM stream_position WHERE stream_id = ?").use { statement ->
            statement.setString(1, streamId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
        }

    private fun saveLastSequence(connection: Connection, streamId: String, sequence: Int) {
        connection.prepareStatement(
            "INSERT INTO stream_position (stream_id, last_sequence) VALUES (?, ?) " +
                "ON CONFLICT (stream_id) DO UPDATE SET last_sequence = EXCLUDED.last_sequence",
        ).use { statement ->
            statement.setString(1, streamId)
            statement.setInt(2, sequence)
            statement.executeUpdate()
        }
    }

    private fun applyEffect(connection: Connection, event: CloudEventMessage) {
        when (event.type) {
            COMMENT_POSTED_TYPE -> insertComment(connection, event)
            COMMENT_EDITED_TYPE -> updateComment(connection, "UPDATE comment_copy SET text = ? WHERE comment_id = ?", event.data["text"].asText(), event.streamId)
            COMMENT_DELETED_TYPE -> updateComment(connection, "UPDATE comment_copy SET status = ? WHERE comment_id = ?", "DELETED", event.streamId)
            else -> throw InvalidEventException("tipo desconocido: ${event.type}")
        }
    }

    private fun insertComment(connection: Connection, event: CloudEventMessage) {
        val videoId = event.data["videoId"].asText()
        val videoIsKnown = connection.prepareStatement("SELECT 1 FROM known_video WHERE video_id = ?").use { statement ->
            statement.setString(1, videoId)
            statement.executeQuery().use { it.next() }
        }
        if (!videoIsKnown) throw UnknownVideoException(videoId)
        connection.prepareStatement(
            "INSERT INTO comment_copy (comment_id, video_id, text, status) VALUES (?, ?, ?, 'ACTIVE')",
        ).use { statement ->
            statement.setString(1, event.streamId)
            statement.setString(2, videoId)
            statement.setString(3, event.data["text"].asText())
            statement.executeUpdate()
        }
    }

    private fun updateComment(connection: Connection, sql: String, newValue: String, commentId: String) {
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, newValue)
            statement.setString(2, commentId)
            statement.executeUpdate()
        }
    }
}
