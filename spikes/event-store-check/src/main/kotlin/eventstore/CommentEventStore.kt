package eventstore

import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import javax.sql.DataSource

private const val UNIQUE_VIOLATION_SQL_STATE = "23505"
private const val DEFAULT_CORRELATION_ID = "cor_01a13161-596e-7cc9-94db-a57740b2d002"

class CommentEventStore(private val dataSource: DataSource) {
    fun readStreamEvents(streamId: String): List<StoredEvent> = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT * FROM comment_events WHERE stream_id = ? ORDER BY version").use { statement ->
            statement.setString(1, streamId)
            statement.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows.toStoredEvent() else null }.toList()
            }
        }
    }

    fun rehydrateComment(commentId: String): CommentState? =
        readStreamEvents(commentId).fold(null as CommentState?) { currentState, event ->
            when (event.type) {
                COMMENT_POSTED_TYPE -> CommentState(
                    commentId = commentId,
                    videoId = event.data["videoId"].asText(),
                    authorId = event.data["authorId"].asText(),
                    text = event.data["text"].asText(),
                    deleted = false,
                    version = event.version,
                )
                COMMENT_EDITED_TYPE -> checkNotNull(currentState).copy(text = event.data["text"].asText(), version = event.version)
                COMMENT_DELETED_TYPE -> checkNotNull(currentState).copy(deleted = true, version = event.version)
                else -> error("tipo desconocido: ${event.type}")
            }
        }

    fun postComment(
        commentId: String,
        videoId: String,
        authorId: String,
        text: String,
        postedAt: Instant = Instant.now(),
    ): StoredEvent = appendEvent(
        streamId = commentId,
        expectedVersion = 0,
        type = COMMENT_POSTED_TYPE,
        data = mapOf(
            "commentId" to commentId,
            "videoId" to videoId,
            "authorId" to authorId,
            "text" to text,
            "postedAt" to postedAt.toString(),
        ),
    )

    fun editComment(commentId: String, newText: String, editedAt: Instant = Instant.now()): StoredEvent {
        val comment = requireExistingComment(commentId)
        if (comment.deleted) throw CommentRejected("el comentario $commentId está borrado")
        return appendEvent(
            streamId = commentId,
            expectedVersion = comment.version,
            type = COMMENT_EDITED_TYPE,
            data = mapOf(
                "commentId" to commentId,
                "videoId" to comment.videoId,
                "text" to newText,
                "editedAt" to editedAt.toString(),
            ),
        )
    }

    fun deleteComment(commentId: String, deletedAt: Instant = Instant.now()): StoredEvent {
        val comment = requireExistingComment(commentId)
        if (comment.deleted) throw CommentRejected("el comentario $commentId ya está borrado")
        return appendEvent(
            streamId = commentId,
            expectedVersion = comment.version,
            type = COMMENT_DELETED_TYPE,
            data = mapOf(
                "commentId" to commentId,
                "videoId" to comment.videoId,
                "deletedAt" to deletedAt.toString(),
            ),
        )
    }

    private fun requireExistingComment(commentId: String): CommentState =
        rehydrateComment(commentId) ?: throw CommentRejected("el comentario $commentId no existe")

    fun appendEvent(
        streamId: String,
        expectedVersion: Int,
        type: String,
        data: Map<String, Any?>,
        metadata: Map<String, Any?> = mapOf("correlationid" to DEFAULT_CORRELATION_ID),
        eventId: String = PrefixedIds.generate("evt"),
        beforeCommit: (Connection) -> Unit = {},
    ): StoredEvent = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val storedEvent = insertEvent(connection, eventId, streamId, expectedVersion + 1, type, data, metadata)
            CommentViewProjection.project(connection, storedEvent)
            beforeCommit(connection)
            connection.commit()
            storedEvent
        } catch (sqlException: SQLException) {
            connection.rollback()
            if (sqlException.isVersionConflict()) throw VersionConflict(streamId, expectedVersion, sqlException)
            throw sqlException
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        }
    }

    private fun insertEvent(
        connection: Connection,
        eventId: String,
        streamId: String,
        version: Int,
        type: String,
        data: Map<String, Any?>,
        metadata: Map<String, Any?>,
    ): StoredEvent = connection.prepareStatement(
        """
        INSERT INTO comment_events (event_id, stream_id, version, type, data, metadata)
        VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)
        RETURNING global_seq, recorded_at
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, eventId)
        statement.setString(2, streamId)
        statement.setInt(3, version)
        statement.setString(4, type)
        statement.setString(5, jsonMapper.writeValueAsString(data))
        statement.setString(6, jsonMapper.writeValueAsString(metadata))
        statement.executeQuery().use { inserted ->
            inserted.next()
            StoredEvent(
                globalSequence = inserted.getLong("global_seq"),
                eventId = eventId,
                streamId = streamId,
                version = version,
                type = type,
                data = jsonMapper.valueToTree(data),
                metadata = jsonMapper.valueToTree(metadata),
                recordedAt = inserted.getTimestamp("recorded_at").toInstant(),
                publishedAt = null,
            )
        }
    }

    private fun SQLException.isVersionConflict() =
        sqlState == UNIQUE_VIOLATION_SQL_STATE && message.orEmpty().contains("stream_id_version")

    fun rebuildCommentView() = dataSource.connection.use { connection ->
        connection.autoCommit = false
        connection.createStatement().use { it.execute("DELETE FROM comment_view") }
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM comment_events ORDER BY global_seq").use { rows ->
                while (rows.next()) CommentViewProjection.project(connection, rows.toStoredEvent())
            }
        }
        connection.commit()
    }
}
