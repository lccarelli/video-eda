package resilience

import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.sql.ResultSet
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource
import kotlin.uuid.Uuid

object PostgresFixture {
    val container = PostgreSQLContainer(DockerImageName.parse("postgres:18.6")).also { it.start() }

    val dataSource: DataSource = PGSimpleDataSource().apply {
        setURL(container.jdbcUrl)
        user = container.username
        password = container.password
    }

    init {
        CommentCopySchema.create(dataSource)
    }

    fun resetTables() = CommentCopySchema.truncateAllTables(dataSource)

    fun <T> query(sql: String, mapRow: (ResultSet) -> T): List<T> = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                val mappedRows = mutableListOf<T>()
                while (rows.next()) mappedRows += mapRow(rows)
                mappedRows
            }
        }
    }

    fun countRows(table: String): Int = query("SELECT count(*) FROM $table") { it.getInt(1) }.single()

    fun markVideoAsKnown(videoId: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO known_video (video_id) VALUES (?) ON CONFLICT DO NOTHING").use {
                it.setString(1, videoId)
                it.executeUpdate()
            }
        }
    }

    fun commentCopy(commentId: String): Pair<String, String>? =
        query("SELECT text, status FROM comment_copy WHERE comment_id = '$commentId'") { it.getString(1) to it.getString(2) }.singleOrNull()
}

object KafkaFixture {
    val container = KafkaContainer(DockerImageName.parse(System.getenv("KAFKA_IMAGE") ?: "apache/kafka:4.3.1")).also { it.start() }
    val bootstrapServers: String get() = container.bootstrapServers

    data class ConsumedRecord(
        val key: String,
        val value: String,
        val partition: Int,
        val offset: Long,
        val headers: Map<String, String>,
    )

    fun createTopic(name: String, partitions: Int = 3) {
        Admin.create(mapOf<String, Any>("bootstrap.servers" to bootstrapServers)).use { admin ->
            admin.createTopics(listOf(NewTopic(name, partitions, 1.toShort()))).all().get()
        }
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

    fun send(producer: KafkaProducer<String, String>, topic: String, key: String, value: String) {
        producer.send(ProducerRecord(topic, key, value)).get()
    }

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
                    polled.forEach { record ->
                        val headers = record.headers().associate { it.key() to String(it.value()) }
                        records += ConsumedRecord(record.key(), record.value(), record.partition(), record.offset(), headers)
                    }
                }
            }
        }
        return records
    }

    fun depthOf(topic: String): Long = KafkaConsumer<String, String>(
        mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
        ),
    ).use { consumer ->
        val partitions = consumer.partitionsFor(topic).map { TopicPartition(topic, it.partition()) }
        consumer.endOffsets(partitions).values.sum()
    }

    fun lagOf(groupId: String, topic: String): Long = Admin.create(mapOf<String, Any>("bootstrap.servers" to bootstrapServers)).use { admin ->
        val committed = admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata().get()
        val partitions = committed.keys.filter { it.topic() == topic }
        val endOffsets = KafkaConsumer<String, String>(
            mapOf<String, Any>(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
            ),
        ).use { it.endOffsets(partitions) }
        partitions.sumOf { endOffsets.getValue(it) - committed.getValue(it).offset() }
    }
}

object Evidence {
    private val directory = File("build/evidence").also { it.mkdirs() }
    fun write(name: String, text: String) {
        File(directory, name).writeText(text)
        println("[EVIDENCE $name]\n$text")
    }
}

fun newId(prefix: String) = "${prefix}_${Uuid.generateV7()}"

object CommentCloudEvents {
    private fun envelope(type: String, commentId: String, sequence: Int, data: Map<String, Any?>): String =
        jsonMapper.writeValueAsString(
            linkedMapOf(
                "specversion" to "1.0",
                "id" to newId("evt"),
                "source" to "/interactions",
                "type" to type,
                "subject" to commentId,
                "time" to "2026-10-16T12:00:00Z",
                "datacontenttype" to "application/json",
                "sequence" to sequence,
                "data" to data,
            ),
        )

    fun posted(commentId: String, videoId: String, text: String) =
        envelope(COMMENT_POSTED_TYPE, commentId, 1, mapOf("commentId" to commentId, "videoId" to videoId, "text" to text))

    fun edited(commentId: String, sequence: Int, text: String) =
        envelope(COMMENT_EDITED_TYPE, commentId, sequence, mapOf("commentId" to commentId, "text" to text))

    fun deleted(commentId: String, sequence: Int) =
        envelope(COMMENT_DELETED_TYPE, commentId, sequence, mapOf("commentId" to commentId))
}
