package resilience

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration
import java.util.UUID

data class RedriveSummary(val applied: Int, val discarded: Int, val stillPending: Int)

class DeadLetterRedrive(
    private val bootstrapServers: String,
    private val deadLetterTopic: String,
    private val handler: CommentCopyHandler,
) {
    fun redrive(): RedriveSummary {
        var applied = 0
        var discarded = 0
        var stillPending = 0
        createReader().use { reader ->
            val partitions = reader.partitionsFor(deadLetterTopic).map { TopicPartition(deadLetterTopic, it.partition()) }
            val endOffsets = reader.endOffsets(partitions)
            for (partition in partitions) {
                reader.assign(listOf(partition))
                reader.seekToBeginning(listOf(partition))
                while (reader.position(partition) < endOffsets.getValue(partition)) {
                    reader.poll(Duration.ofMillis(500)).forEach { record ->
                        val outcome = runCatching { handler.handle(CloudEventMessage.parse(record.value())) }
                        when {
                            outcome.isFailure -> stillPending++
                            outcome.getOrThrow() == HandleOutcome.APPLIED -> applied++
                            outcome.getOrThrow() == HandleOutcome.SEQUENCE_GAP -> stillPending++
                            else -> discarded++
                        }
                    }
                }
            }
        }
        return RedriveSummary(applied, discarded, stillPending)
    }

    private fun createReader() = KafkaConsumer<String, String>(
        mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "redrive-${UUID.randomUUID()}",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
        ),
    )
}
