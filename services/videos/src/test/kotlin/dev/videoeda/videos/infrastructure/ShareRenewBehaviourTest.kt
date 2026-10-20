package dev.videoeda.videos.infrastructure

import org.apache.kafka.clients.consumer.AcknowledgeType
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

class ShareRenewBehaviourTest {
    @Test
    fun `tras un RENEW el poll devuelve solo el mismo registro, con la misma deliveryCount, aunque haya mas mensajes en cola`() {
        val testId = UUID.randomUUID().toString().take(8)
        val topic = "renew-behaviour.$testId"
        val groupId = "renew-behaviour-$testId"
        KafkaFixture.createTopic(topic, partitions = 1)
        KafkaFixture.configureShareGroup(groupId, recordLockDurationMillis = 3_000)
        KafkaFixture.newProducer().use { producer ->
            listOf("primero", "segundo", "tercero").forEach { producer.send(ProducerRecord(topic, it, "valor-$it")).get() }
        }

        newProcessingShareConsumer(KafkaFixture.bootstrapServers, groupId).use { consumer ->
            consumer.subscribe(listOf(topic))
            var held = pollOneRecord(consumer, timeoutSeconds = 40)
            val firstDeliveryCount = held.deliveryCount().get()
            val observations = mutableListOf<String>()

            repeat(4) { renewal ->
                Thread.sleep(1_000)
                consumer.acknowledge(held, AcknowledgeType.RENEW)
                consumer.commitSync()
                val polled = pollOncePerAttemptUntilFirstRecord(consumer)
                    ?: error("el poll posterior al RENEW #${renewal + 1} no devolvió nada en 10 intentos")
                observations +=
                    "renovación ${renewal + 1}: ${polled.map { "${it.key()}@${it.offset()} dc=${it.deliveryCount().orElse(null)}" }}"
                assertEquals(1, polled.size, "el poll posterior al RENEW devolvió más de un registro: $observations")
                held = polled.single()
                assertEquals("primero", held.key(), "devolvió otro registro en lugar del renovado: $observations")
                assertEquals(firstDeliveryCount, held.deliveryCount().get(), "el RENEW sumó una entrega: $observations")
            }
            println("SHARE-RENEW 4 renovaciones sobre un lock de 3 s (4 s de trabajo) con 2 mensajes más en cola: $observations")

            consumer.acknowledge(held, AcknowledgeType.ACCEPT)
            consumer.commitSync()
            val next = pollOneRecord(consumer, timeoutSeconds = 20)
            assertEquals("segundo", next.key(), "tras confirmar, el siguiente mensaje en orden de offset")
            assertTrue(next.deliveryCount().get() == 1.toShort(), "el siguiente mensaje llega en su primera entrega")
        }
    }

    private fun pollOncePerAttemptUntilFirstRecord(
        consumer: org.apache.kafka.clients.consumer.KafkaShareConsumer<String, String>,
    ): List<ConsumerRecord<String, String>>? =
        generateSequence { consumer.poll(Duration.ofMillis(500)).toList() }.take(10).firstOrNull { it.isNotEmpty() }

    private fun pollOneRecord(
        consumer: org.apache.kafka.clients.consumer.KafkaShareConsumer<String, String>,
        timeoutSeconds: Long,
    ): ConsumerRecord<String, String> {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1_000
        while (System.currentTimeMillis() < deadline) {
            consumer.poll(Duration.ofMillis(500)).firstOrNull()?.let { return it }
        }
        error("no llegó ningún registro en $timeoutSeconds s")
    }
}
