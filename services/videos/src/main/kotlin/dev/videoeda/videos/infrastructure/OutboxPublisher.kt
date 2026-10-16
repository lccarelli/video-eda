package dev.videoeda.videos.infrastructure

import dev.videoeda.videos.application.CloudEvents
import dev.videoeda.videos.application.OutboxEntry
import dev.videoeda.videos.domain.CorrelationId
import dev.videoeda.videos.domain.EventId
import dev.videoeda.videos.domain.VideoId
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import javax.sql.DataSource

class OutboxPublisher(
    private val dataSource: DataSource,
    private val jdbc: JdbcClient,
    private val sender: OutboxMessageSender,
    private val jsonMapper: JsonMapper = JsonMapper.builder().build(),
    private val batchSize: Int = 100,
) {
    private val log = LoggerFactory.getLogger(OutboxPublisher::class.java)

    fun publishPendingEvents(): Int = dataSource.connection.use { lockConnection ->
        if (!lockConnection.tryAcquirePublisherLock()) return 0
        try {
            publishInOrderStoppingAtTheFirstFailure()
        } finally {
            lockConnection.releasePublisherLock()
        }
    }

    private fun publishInOrderStoppingAtTheFirstFailure(): Int {
        var publishedCount = 0
        for (message in selectPendingMessages()) {
            try {
                val receipt = sender.send(message)
                markAsPublished(message)
                logPublished(message, receipt)
                publishedCount++
            } catch (failure: PublishFailedException) {
                log.warn("no se pudo publicar eventId={} en {}: queda pendiente", message.eventId, message.topic, failure)
                break
            }
        }
        return publishedCount
    }

    private fun selectPendingMessages(): List<OutboxMessage> = jdbc.sql(
        """
        SELECT outbox_seq, event_id, topic, aggregate_id, type, occurred_at, correlation_id, sequence, data::text AS data
        FROM outbox WHERE published_at IS NULL ORDER BY outbox_seq LIMIT :batchSize
        """.trimIndent(),
    )
        .param("batchSize", batchSize)
        .query { rows, _ ->
            val entry = OutboxEntry(
                eventId = EventId(rows.getString("event_id")),
                topic = rows.getString("topic"),
                aggregateId = VideoId(rows.getString("aggregate_id")),
                type = rows.getString("type"),
                occurredAt = rows.getTimestamp("occurred_at").toInstant(),
                correlationId = CorrelationId(rows.getString("correlation_id")),
                data = jsonMapper.readValue(rows.getString("data"), object : TypeReference<Map<String, Any?>>() {}),
            )
            val sequence = rows.getInt("sequence")
            OutboxMessage(
                outboxSequence = rows.getLong("outbox_seq"),
                topic = entry.topic,
                key = entry.aggregateId.value,
                cloudEventJson = jsonMapper.writeValueAsString(CloudEvents.toMap(entry, sequence)),
                eventId = entry.eventId.value,
                correlationId = entry.correlationId.value,
                sequence = sequence,
            )
        }
        .list()

    private fun markAsPublished(message: OutboxMessage) {
        jdbc.sql("UPDATE outbox SET published_at = now() WHERE outbox_seq = :outboxSequence AND published_at IS NULL")
            .param("outboxSequence", message.outboxSequence)
            .update()
    }

    private fun logPublished(message: OutboxMessage, receipt: DeliveryReceipt) = withLogContext(
        "correlationId" to message.correlationId,
        "eventId" to message.eventId,
        "aggregateId" to message.key,
        "sequence" to message.sequence.toString(),
        "topic" to message.topic,
        "partition" to receipt.partition.toString(),
        "offset" to receipt.offset.toString(),
    ) { log.info("evento publicado") }
}
