package dev.videoeda.videos.infrastructure

import dev.videoeda.videos.application.OutboxEntry
import dev.videoeda.videos.application.OutboxWriter
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp

class JdbcOutboxWriter(private val jdbc: JdbcClient, private val jsonMapper: JsonMapper = JsonMapper.builder().build()) : OutboxWriter {
    override fun appendInCurrentTransaction(entry: OutboxEntry) {
        jdbc.sql(
            """
            INSERT INTO outbox (event_id, topic, aggregate_id, type, occurred_at, correlation_id, sequence, data)
            SELECT :eventId, :topic, :aggregateId, :type, :occurredAt, :correlationId, coalesce(max(sequence), 0) + 1, :data::jsonb
            FROM outbox
            WHERE aggregate_id = :aggregateId AND topic = :topic
            """.trimIndent(),
        )
            .param("eventId", entry.eventId.value)
            .param("topic", entry.topic)
            .param("aggregateId", entry.aggregateId.value)
            .param("type", entry.type)
            .param("occurredAt", Timestamp.from(entry.occurredAt))
            .param("correlationId", entry.correlationId.value)
            .param("data", jsonMapper.writeValueAsString(entry.data))
            .update()
    }
}
