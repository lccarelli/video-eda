package dev.videoeda.videos.infrastructure

import org.apache.kafka.clients.consumer.KafkaShareConsumer
import org.apache.kafka.common.serialization.StringDeserializer

const val PROCESSING_GROUP_ID = "videos-processing-worker"

fun newProcessingShareConsumer(bootstrapServers: String, groupId: String = PROCESSING_GROUP_ID) = KafkaShareConsumer<String, String>(
    mapOf<String, Any>(
        "bootstrap.servers" to bootstrapServers,
        "group.id" to groupId,
        "key.deserializer" to StringDeserializer::class.java.name,
        "value.deserializer" to StringDeserializer::class.java.name,
        "share.acknowledgement.mode" to "explicit",
        "share.acquire.mode" to "record_limit",
        "max.poll.records" to 1,
    ),
)
