package dev.videoeda.videos.infrastructure

import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.KafkaException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class KafkaOutboxMessageSender(private val producer: KafkaProducer<String, String>, private val acknowledgementTimeoutSeconds: Long = 30) :
    OutboxMessageSender {
    override fun send(message: OutboxMessage): DeliveryReceipt = try {
        val metadata = producer.send(ProducerRecord(message.topic, message.key, message.cloudEventJson))
            .get(acknowledgementTimeoutSeconds, TimeUnit.SECONDS)
        DeliveryReceipt(metadata.partition(), metadata.offset())
    } catch (failure: KafkaException) {
        throw PublishFailedException("Kafka rechazó o no pudo recibir ${message.eventId}", failure)
    } catch (failure: ExecutionException) {
        throw PublishFailedException("Kafka no confirmó ${message.eventId}", failure)
    } catch (failure: TimeoutException) {
        throw PublishFailedException("Kafka no confirmó ${message.eventId} a tiempo", failure)
    }
}
