package dev.videoeda.videos.infrastructure

import dev.videoeda.videos.application.OutboxWriter
import dev.videoeda.videos.application.Transaction
import dev.videoeda.videos.application.VideoQueries
import dev.videoeda.videos.application.VideoRepository
import dev.videoeda.videos.application.VideoService
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import javax.sql.DataSource

@Configuration
class VideosConfiguration {
    @Bean fun clock(): Clock = Clock.systemUTC()

    @Bean fun jdbcClient(dataSource: DataSource): JdbcClient = JdbcClient.create(dataSource)

    @Bean fun transaction(transactionManager: PlatformTransactionManager): Transaction = SpringTransaction(transactionManager)

    @Bean fun videoRepository(jdbc: JdbcClient): VideoRepository = JdbcVideoRepository(jdbc)

    @Bean fun outboxWriter(jdbc: JdbcClient): OutboxWriter = JdbcOutboxWriter(jdbc)

    @Bean fun videoQueries(jdbc: JdbcClient): VideoQueries = JdbcVideoQueries(jdbc)

    @Bean fun videoService(repository: VideoRepository, outbox: OutboxWriter, transaction: Transaction, clock: Clock) =
        VideoService(repository, outbox, transaction, clock)

    @Bean(destroyMethod = "close")
    fun kafkaProducer(@Value("\${videos.kafka.bootstrap-servers}") bootstrapServers: String): KafkaProducer<String, String> = KafkaProducer(
        mapOf<String, Any>(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java.name,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java.name,
            ProducerConfig.ACKS_CONFIG to "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
        ),
    )

    @Bean fun outboxMessageSender(producer: KafkaProducer<String, String>): OutboxMessageSender = KafkaOutboxMessageSender(producer)

    @Bean fun outboxPublisher(dataSource: DataSource, jdbc: JdbcClient, sender: OutboxMessageSender): OutboxPublisher =
        OutboxPublisher(dataSource, jdbc, sender, JsonMapper.builder().build())
}
