package resilience

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration

class SimulatedCrash : RuntimeException("caída simulada antes de confirmar el offset")

enum class DeadLetterCause { RETRIES_EXHAUSTED, SEQUENCE_GAP, PERMANENT_INVALID_EVENT }

data class ConsumerSettings(
    val maxAttempts: Int = 3,
    val baseBackoffMillis: Long = 20,
    val maxPollIntervalMillis: Int = 300_000,

    val keepAliveWhileWaitingForDependency: Boolean = true,
    val dependencyRetryIntervalMillis: Long = 200,
)

data class ProcessingSummary(
    val applied: Int = 0,
    val discarded: Int = 0,
    val deadLettered: Int = 0,
    val groupGenerationIds: Set<Int> = emptySet(),
)

class CommentCopyConsumer(
    private val bootstrapServers: String,
    private val topic: String,
    private val deadLetterTopic: String,
    private val groupId: String,
    private val handler: CommentCopyHandler,
    private val deadLetterProducer: KafkaProducer<String, String>,
    private val settings: ConsumerSettings = ConsumerSettings(),
) {
    private var applied = 0
    private var discarded = 0
    private var deadLettered = 0
    private val generationIds = mutableSetOf<Int>()

    fun processUntilIdle(crashBeforeCommit: Boolean = false): ProcessingSummary {
        createConsumer().use { consumer ->
            consumer.subscribe(listOf(topic))
            var consecutiveEmptyPolls = 0
            while (consecutiveEmptyPolls < 2) {
                val records = consumer.poll(Duration.ofSeconds(1))
                generationIds += consumer.groupMetadata().generationId()
                if (records.isEmpty) {
                    consecutiveEmptyPolls++
                    continue
                }
                consecutiveEmptyPolls = 0
                records.forEach { processRecord(consumer, it) }
                if (crashBeforeCommit) throw SimulatedCrash()
                consumer.commitSync()
            }
        }
        return ProcessingSummary(applied, discarded, deadLettered, generationIds.toSet())
    }

    private fun processRecord(consumer: KafkaConsumer<String, String>, record: ConsumerRecord<String, String>) {
        val event = try {
            CloudEventMessage.parse(record.value())
        } catch (invalid: InvalidEventException) {
            return deadLetter(record, DeadLetterCause.PERMANENT_INVALID_EVENT, attempts = 1, reason = invalid.message)
        }
        var attempt = 0
        while (true) {
            attempt++
            try {
                when (handler.handle(event)) {
                    HandleOutcome.APPLIED -> applied++
                    HandleOutcome.DISCARDED_DUPLICATE, HandleOutcome.DISCARDED_OLD -> discarded++
                    HandleOutcome.SEQUENCE_GAP -> deadLetter(record, DeadLetterCause.SEQUENCE_GAP, attempt, "hueco de sequence")
                }
                return
            } catch (unknownVideo: UnknownVideoException) {
                if (attempt >= settings.maxAttempts) {
                    return deadLetter(record, DeadLetterCause.RETRIES_EXHAUSTED, attempt, unknownVideo.message)
                }
                Thread.sleep(settings.baseBackoffMillis shl (attempt - 1))
            } catch (invalid: InvalidEventException) {
                return deadLetter(record, DeadLetterCause.PERMANENT_INVALID_EVENT, attempt, invalid.message)
            } catch (dependencyDown: DependencyUnavailableException) {
                waitForDependency(consumer)
                attempt--
            }
        }
    }

    private fun waitForDependency(consumer: KafkaConsumer<String, String>) {
        if (settings.keepAliveWhileWaitingForDependency) {
            val pausedPartitions = consumer.assignment()
            consumer.pause(pausedPartitions)

            val deadline = System.currentTimeMillis() + settings.dependencyRetryIntervalMillis
            while (System.currentTimeMillis() < deadline) consumer.poll(Duration.ofMillis(50))
            consumer.resume(pausedPartitions)
        } else {
            Thread.sleep(settings.dependencyRetryIntervalMillis)
        }
    }

    private fun deadLetter(record: ConsumerRecord<String, String>, cause: DeadLetterCause, attempts: Int, reason: String?) {
        val deadLetterRecord = ProducerRecord(deadLetterTopic, record.partition(), record.key(), record.value())
        fun header(name: String, value: String) = deadLetterRecord.headers().add(name, value.toByteArray())
        header("dlt-cause", cause.name)
        header("dlt-reason", reason.orEmpty())
        header("dlt-attempts", attempts.toString())
        header("dlt-original-topic", record.topic())
        header("dlt-original-partition", record.partition().toString())
        header("dlt-original-offset", record.offset().toString())
        deadLetterProducer.send(deadLetterRecord).get()
        deadLettered++
    }

    private fun createConsumer() = KafkaConsumer<String, String>(
        mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to groupId,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to settings.maxPollIntervalMillis,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
        ),
    )
}
