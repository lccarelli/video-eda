package resilience

import javax.sql.DataSource

object CommentCopySchema {
    private val creationScript = """
        CREATE TABLE known_video (video_id TEXT PRIMARY KEY);
        CREATE TABLE comment_copy (
          comment_id TEXT PRIMARY KEY,
          video_id   TEXT NOT NULL,
          text       TEXT NOT NULL,
          status     TEXT NOT NULL CHECK (status IN ('ACTIVE', 'DELETED'))
        );
        CREATE TABLE stream_position (
          stream_id     TEXT PRIMARY KEY,
          last_sequence INT NOT NULL
        );
        CREATE TABLE inbox (
          source TEXT NOT NULL,
          id     TEXT NOT NULL,
          PRIMARY KEY (source, id)
        );
    """.trimIndent()

    fun create(dataSource: DataSource) = execute(dataSource, creationScript)

    fun truncateAllTables(dataSource: DataSource) =
        execute(dataSource, "TRUNCATE known_video, comment_copy, stream_position, inbox")

    private fun execute(dataSource: DataSource, sql: String) {
        dataSource.connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
    }
}
