package eventstore

import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.ResultSet
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

object PostgresFixture {
    val container = PostgreSQLContainer(DockerImageName.parse("postgres:18.6")).also { it.start() }

    val dataSource: DataSource = PGSimpleDataSource().apply {
        setURL(container.jdbcUrl)
        user = container.username
        password = container.password
    }

    init {
        DatabaseSchema.create(dataSource)
    }

    fun resetTables() = DatabaseSchema.truncateAllTables(dataSource)

    fun <T> query(sql: String, mapRow: (ResultSet) -> T): List<T> = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                val mappedRows = mutableListOf<T>()
                while (rows.next()) mappedRows += mapRow(rows)
                mappedRows
            }
        }
    }

    fun countRows(tableExpression: String): Int =
        query("SELECT count(*) FROM $tableExpression") { it.getInt(1) }.single()
}

object KafkaFixture {
    val container = KafkaContainer(DockerImageName.parse(System.getenv("KAFKA_IMAGE") ?: "apache/kafka:4.3.1")).also { it.start() }
    val bootstrapServers: String get() = container.bootstrapServers

    fun createUniqueTopic(prefix: String = "interactions.comment"): String {
        val topicName = "$prefix-${UUID.randomUUID().toString().take(8)}"
        Admin.create(mapOf<String, Any>("bootstrap.servers" to bootstrapServers)).use { admin ->
            admin.createTopics(listOf(NewTopic(topicName, 3, 1.toShort()))).all().get()
        }
        return topicName
    }

    fun createIdempotentProducer() = KafkaProducer<String, String>(
        mapOf<String, Any>(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java.name,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java.name,
            ProducerConfig.ACKS_CONFIG to "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
        ),
    )

    data class ConsumedRecord(val key: String, val value: String, val partition: Int, val offset: Long)

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
                if (polled.isEmpty) {
                    consecutiveEmptyPolls++
                } else {
                    consecutiveEmptyPolls = 0
                    polled.forEach { records += ConsumedRecord(it.key(), it.value(), it.partition(), it.offset()) }
                }
            }
        }
        return records
    }
}
