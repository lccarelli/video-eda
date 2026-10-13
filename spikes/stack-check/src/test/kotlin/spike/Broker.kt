package spike

import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AlterConfigOp
import org.apache.kafka.clients.admin.ConfigEntry
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.config.ConfigResource
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName

object Broker {
    val container: KafkaContainer = KafkaContainer(DockerImageName.parse(System.getenv("KAFKA_IMAGE") ?: "apache/kafka:4.3.1"))

        .withEnv("KAFKA_SHARE_COORDINATOR_STATE_TOPIC_REPLICATION_FACTOR", "1")
        .withEnv("KAFKA_SHARE_COORDINATOR_STATE_TOPIC_MIN_ISR", "1")

        .withEnv("KAFKA_GROUP_SHARE_MIN_RECORD_LOCK_DURATION_MS", "1000")

    init {
        container.start()
    }

    val bootstrapServers: String get() = container.bootstrapServers

    fun <T> withAdmin(block: (Admin) -> T): T =
        Admin.create(mapOf<String, Any>("bootstrap.servers" to bootstrapServers)).use(block)

    fun createTopic(name: String, partitions: Int = 3) {
        withAdmin { it.createTopics(listOf(NewTopic(name, partitions, 1.toShort()))).all().get() }
    }

    fun setGroupConfig(group: String, vararg settings: Pair<String, String>) {
        val groupResource = ConfigResource(ConfigResource.Type.GROUP, group)
        val setOperations = settings.map { (name, value) -> AlterConfigOp(ConfigEntry(name, value), AlterConfigOp.OpType.SET) }
        withAdmin { it.incrementalAlterConfigs(mapOf(groupResource to setOperations)).all().get() }
    }

    fun describeGroupConfig(group: String): Map<String, String?> {
        val groupResource = ConfigResource(ConfigResource.Type.GROUP, group)
        return withAdmin { admin ->
            admin.describeConfigs(listOf(groupResource)).all().get().getValue(groupResource).entries()
                .associate { it.name() to it.value() }
        }
    }

    fun produceRecords(topic: String, keyedValues: List<Pair<String, String>>) {
        val producerConfig = mapOf<String, Any>(
            "bootstrap.servers" to bootstrapServers,
            "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
            "value.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
            "acks" to "all",
        )
        KafkaProducer<String, String>(producerConfig).use { producer ->
            keyedValues.forEach { (key, value) -> producer.send(ProducerRecord(topic, key, value)).get() }
        }
    }
}

fun awaitUntil(timeoutMs: Long = 30_000, stepMs: Long = 100, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(stepMs)
    }
    return condition()
}
