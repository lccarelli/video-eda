package eventstore

import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import java.sql.Connection
import javax.sql.DataSource

class SimulatedCrash : RuntimeException("caída simulada entre el envío y el UPDATE de published_at")

enum class RowLocking {
    NONE,

    SKIP_LOCKED,
}

class CommentEventPublisher(
    private val dataSource: DataSource,
    private val producer: KafkaProducer<String, String>,
    private val topic: String,
    private val rowLocking: RowLocking = RowLocking.NONE,
) {
    fun publishPendingEvents(
        batchSize: Int = 100,
        simulateCrashAfterFirstSend: Boolean = false,
        afterReadingPendingEvents: () -> Unit = {},
    ): Int = dataSource.connection.use { connection ->
        when (rowLocking) {
            RowLocking.NONE ->
                publishWithoutLocking(connection, batchSize, simulateCrashAfterFirstSend, afterReadingPendingEvents)
            RowLocking.SKIP_LOCKED ->
                publishWithSkipLocked(connection, batchSize, simulateCrashAfterFirstSend, afterReadingPendingEvents)
        }
    }

    private fun publishWithoutLocking(
        connection: Connection,
        batchSize: Int,
        simulateCrashAfterFirstSend: Boolean,
        afterReadingPendingEvents: () -> Unit,
    ): Int {
        connection.autoCommit = true
        val pendingEvents = selectUnpublishedEvents(connection, batchSize, lockRows = false)
        afterReadingPendingEvents()
        return sendAndMark(connection, pendingEvents, simulateCrashAfterFirstSend)
    }

    private fun publishWithSkipLocked(
        connection: Connection,
        batchSize: Int,
        simulateCrashAfterFirstSend: Boolean,
        afterReadingPendingEvents: () -> Unit,
    ): Int {
        connection.autoCommit = false
        try {
            val pendingEvents = selectUnpublishedEvents(connection, batchSize, lockRows = true)
            afterReadingPendingEvents()
            val publishedCount = sendAndMark(connection, pendingEvents, simulateCrashAfterFirstSend)
            connection.commit()
            return publishedCount
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        }
    }

    private fun sendAndMark(connection: Connection, events: List<StoredEvent>, simulateCrashAfterFirstSend: Boolean): Int {
        var publishedCount = 0
        for (event in events) {
            sendToKafka(event)
            if (simulateCrashAfterFirstSend) throw SimulatedCrash()
            markAsPublished(connection, event)
            publishedCount++
        }
        return publishedCount
    }

    private fun selectUnpublishedEvents(connection: Connection, batchSize: Int, lockRows: Boolean): List<StoredEvent> {
        val sql = "SELECT * FROM comment_events WHERE published_at IS NULL ORDER BY global_seq LIMIT ?" +
            if (lockRows) " FOR UPDATE SKIP LOCKED" else ""
        return connection.prepareStatement(sql).use { statement ->
            statement.setInt(1, batchSize)
            statement.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows.toStoredEvent() else null }.toList()
            }
        }
    }

    private fun sendToKafka(event: StoredEvent) {
        producer.send(ProducerRecord(topic, event.streamId, toCloudEventJson(event))).get()
    }

    private fun markAsPublished(connection: Connection, event: StoredEvent) {
        connection.prepareStatement(
            "UPDATE comment_events SET published_at = now() WHERE event_id = ? AND published_at IS NULL",
        ).use { statement ->
            statement.setString(1, event.eventId)
            statement.executeUpdate()
        }
    }

    companion object {
        fun toCloudEventJson(event: StoredEvent): String {
            val cloudEvent = linkedMapOf<String, Any?>(
                "specversion" to "1.0",
                "id" to event.eventId,
                "source" to "/interactions",
                "type" to event.type,
                "subject" to event.streamId,
                "time" to event.recordedAt.toString(),
                "datacontenttype" to "application/json",
            )
            event.metadata["correlationid"]?.let { cloudEvent["correlationid"] = it.asText() }
            cloudEvent["sequence"] = event.version
            cloudEvent["data"] = jsonMapper.convertValue(event.data, Map::class.java)
            return jsonMapper.writeValueAsString(cloudEvent)
        }
    }
}
