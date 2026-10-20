package dev.videoeda.videos.infrastructure

data class OutboxMessage(
    val outboxSequence: Long,
    val topic: String,
    val key: String,
    val cloudEventJson: String,
    val eventId: String,
    val correlationId: String,
    val sequence: Int,
)

data class DeliveryReceipt(val partition: Int, val offset: Long)

class PublishFailedException(message: String, cause: Throwable) : RuntimeException(message, cause)

interface OutboxMessageSender {
    fun send(message: OutboxMessage): DeliveryReceipt
}
