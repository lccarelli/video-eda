package eventstore

import javax.sql.DataSource

object DatabaseSchema {
    private const val UUID_V7_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"

    val creationScript = """
        CREATE TABLE comment_events (
          global_seq   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
          event_id     TEXT        NOT NULL UNIQUE,
          stream_id    TEXT        NOT NULL,
          version      INT         NOT NULL,
          type         TEXT        NOT NULL,
          data         JSONB       NOT NULL,
          metadata     JSONB       NOT NULL,
          recorded_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
          published_at TIMESTAMPTZ,
          UNIQUE (stream_id, version),
          CHECK (stream_id ~ '^cmt_$UUID_V7_PATTERN$'),
          CHECK (event_id  ~ '^evt_$UUID_V7_PATTERN$')
        );
        CREATE INDEX comment_events_unpublished ON comment_events (global_seq) WHERE published_at IS NULL;

        CREATE TABLE comment_view (
          comment_id TEXT        PRIMARY KEY,
          video_id   TEXT        NOT NULL,
          author_id  TEXT        NOT NULL,
          text       TEXT        NOT NULL,
          status     TEXT        NOT NULL CHECK (status IN ('ACTIVE', 'DELETED')),
          version    INT         NOT NULL,
          posted_at  TIMESTAMPTZ NOT NULL,
          updated_at TIMESTAMPTZ NOT NULL
        );

        CREATE TABLE inbox (
          source TEXT NOT NULL,
          id     TEXT NOT NULL,
          PRIMARY KEY (source, id)
        );

        CREATE TABLE notification_effect (
          effect_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
          event_id  TEXT NOT NULL
        );
    """.trimIndent()

    fun create(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute(creationScript) }
        }
    }

    fun truncateAllTables(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute("TRUNCATE comment_events, comment_view, inbox, notification_effect RESTART IDENTITY")
            }
        }
    }
}
