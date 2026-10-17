package dev.videoeda.videos.infrastructure

import dev.videoeda.videos.application.Topics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import java.util.concurrent.TimeUnit

class KafkaDeadLetterPublisher(
    private val producer: KafkaProducer<String, String>,
    private val deadLetterTopic: String = Topics.VIDEOS_PROCESSING_DLQ,
    private val acknowledgementTimeoutSeconds: Long = 30,
) {
    fun publish(origin: ConsumerRecord<String, String>, cause: String, attempts: Int, eventId: String?) {
        val headers = buildList {
            add(header(CAUSE_HEADER, cause))
            add(header(ATTEMPTS_HEADER, attempts.toString()))
            eventId?.let { add(header(EVENT_ID_HEADER, it)) }
            add(header(ORIGIN_TOPIC_HEADER, origin.topic()))
            add(header(ORIGIN_PARTITION_HEADER, origin.partition().toString()))
            add(header(ORIGIN_OFFSET_HEADER, origin.offset().toString()))
        }
        producer.send(ProducerRecord(deadLetterTopic, origin.partition(), origin.key(), origin.value(), headers))
            .get(acknowledgementTimeoutSeconds, TimeUnit.SECONDS)
    }

    private fun header(name: String, value: String) = RecordHeader(name, value.toByteArray())

    companion object {
        const val CAUSE_HEADER = "dlq-cause"
        const val ATTEMPTS_HEADER = "dlq-attempts"
        const val EVENT_ID_HEADER = "dlq-event-id"
        const val ORIGIN_TOPIC_HEADER = "dlq-origin-topic"
        const val ORIGIN_PARTITION_HEADER = "dlq-origin-partition"
        const val ORIGIN_OFFSET_HEADER = "dlq-origin-offset"
    }
}
