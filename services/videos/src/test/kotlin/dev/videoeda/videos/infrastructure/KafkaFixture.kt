package dev.videoeda.videos.infrastructure

import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AlterConfigOp
import org.apache.kafka.clients.admin.ConfigEntry
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.UUID

object KafkaFixture {
    private val container = KafkaContainer(DockerImageName.parse(System.getenv("KAFKA_IMAGE") ?: "apache/kafka:4.3.1"))
        .withSingleNodeShareCoordinator()
        .withShortRecordLocksAllowed()
        .also { it.start() }
    val bootstrapServers: String get() = container.bootstrapServers

    private fun KafkaContainer.withSingleNodeShareCoordinator() = this
        .withEnv("KAFKA_SHARE_COORDINATOR_STATE_TOPIC_REPLICATION_FACTOR", "1")
        .withEnv("KAFKA_SHARE_COORDINATOR_STATE_TOPIC_MIN_ISR", "1")

    private fun KafkaContainer.withShortRecordLocksAllowed() = withEnv("KAFKA_GROUP_SHARE_MIN_RECORD_LOCK_DURATION_MS", "1000")

    data class ConsumedRecord(
        val key: String,
        val value: String,
        val partition: Int,
        val offset: Long,
        val headers: Map<String, String> = emptyMap(),
    )

    init {
        Admin.create(mapOf<String, Any>("bootstrap.servers" to bootstrapServers)).use { admin ->
            admin.createTopics(
                listOf("videos.video", "videos.processing", "videos.processing.dlq").map {
                    NewTopic(it, 3, 1.toShort())
                },
            ).all().get()
        }
    }

    fun createTopic(name: String, partitions: Int = 3) {
        Admin.create(mapOf<String, Any>("bootstrap.servers" to bootstrapServers)).use { admin ->
            admin.createTopics(listOf(NewTopic(name, partitions, 1.toShort()))).all().get()
        }
    }

    fun configureShareGroup(groupId: String, recordLockDurationMillis: Long) {
        val group = ConfigResource(ConfigResource.Type.GROUP, groupId)
        val settings = mapOf(
            "share.auto.offset.reset" to "earliest",
            "share.record.lock.duration.ms" to recordLockDurationMillis.toString(),
        )
        Admin.create(mapOf<String, Any>("bootstrap.servers" to bootstrapServers)).use { admin ->
            val operations = settings.map { (name, value) -> AlterConfigOp(ConfigEntry(name, value), AlterConfigOp.OpType.SET) }
            admin.incrementalAlterConfigs(mapOf(group to operations)).all().get()
            val deadline = System.currentTimeMillis() + 10_000
            while (admin.describeConfigs(listOf(group)).all().get().getValue(group).get("share.record.lock.duration.ms")?.value() !=
                settings.getValue("share.record.lock.duration.ms")
            ) {
                check(System.currentTimeMillis() < deadline) { "el broker no aplicó la configuración del share group $groupId" }
                Thread.sleep(100)
            }
        }
    }

    fun newProducer(extraConfig: Map<String, Any> = emptyMap(), bootstrap: String = bootstrapServers) = KafkaProducer<String, String>(
        mapOf<String, Any>(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java.name,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java.name,
            ProducerConfig.ACKS_CONFIG to "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
        ) + extraConfig,
    )

    fun readAllRecords(topic: String): List<ConsumedRecord> {
        val consumerConfig = mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "reader-${UUID.randomUUID()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
        )
        val records = mutableListOf<ConsumedRecord>()
        KafkaConsumer<String, String>(consumerConfig).use { consumer ->
            consumer.subscribe(listOf(topic))
            var consecutiveEmptyPolls = 0
            while (consecutiveEmptyPolls < 2) {
                val polled = consumer.poll(Duration.ofSeconds(1))
                consecutiveEmptyPolls = if (polled.isEmpty) consecutiveEmptyPolls + 1 else 0
                polled.forEach { records += ConsumedRecord(it.key(), it.value(), it.partition(), it.offset()) }
            }
        }
        return records
    }

    fun awaitRecordsWithKey(topic: String, key: String, expectedCount: Int = 1, timeoutSeconds: Long = 20): List<ConsumedRecord> {
        val consumerConfig = mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "awaiter-${UUID.randomUUID()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
        )
        val found = mutableListOf<ConsumedRecord>()
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1_000
        KafkaConsumer<String, String>(consumerConfig).use { consumer ->
            consumer.subscribe(listOf(topic))
            while (found.size < expectedCount && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).filter { it.key() == key }
                    .forEach {
                        val headers = it.headers().associate { header -> header.key() to String(header.value()) }
                        found += ConsumedRecord(it.key(), it.value(), it.partition(), it.offset(), headers)
                    }
            }
        }
        return found
    }
}
