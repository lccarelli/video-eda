package dev.videoeda.videos.infrastructure

import org.apache.kafka.clients.consumer.AcknowledgeType
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaShareConsumer
import org.slf4j.LoggerFactory
import java.time.Duration

class InFlightShareRecord(private val consumer: KafkaShareConsumer<String, String>, private val pollTimeout: Duration) {
    private val log = LoggerFactory.getLogger(InFlightShareRecord::class.java)
    private var current: ConsumerRecord<String, String>? = null

    fun hold(record: ConsumerRecord<String, String>) {
        current = record
    }

    fun drop() {
        current = null
    }

    fun acknowledge(type: AcknowledgeType) {
        val record = checkNotNull(current) { "no hay un registro en vuelo para $type" }
        consumer.acknowledge(record, type)
        consumer.commitSync().forEach { (partition, failure) ->
            failure.ifPresent { log.warn("el broker rechazó el {} de {}: {}", type, partition, it.message) }
        }
    }

    fun renew() {
        acknowledge(AcknowledgeType.RENEW)
        val renewed = pollRenewedRecord()
        if (renewed == null) log.warn("el broker no devolvió el registro renovado: el lock pudo perderse") else current = renewed
    }

    private fun pollRenewedRecord(): ConsumerRecord<String, String>? {
        repeat(POLLS_TO_RECEIVE_THE_RENEWED_RECORD) {
            consumer.poll(pollTimeout).firstOrNull()?.let { return it }
        }
        return null
    }

    private companion object {
        const val POLLS_TO_RECEIVE_THE_RENEWED_RECORD = 10
    }
}
