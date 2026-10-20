package dev.videoeda.videos.infrastructure

import dev.videoeda.videos.application.ProcessVideoRequestHandler
import dev.videoeda.videos.application.VideoQueries
import dev.videoeda.videos.application.VideoService
import dev.videoeda.videos.application.VideoTranscoder
import org.apache.kafka.clients.producer.KafkaProducer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
@ConditionalOnProperty(name = ["videos.worker.enabled"], havingValue = "true")
class ProcessVideoWorkerConfiguration {
    @Bean fun videoTranscoder(
        @Value("\${videos.worker.processing-time}") processingTime: Duration,
        @Value("\${videos.worker.keep-alive-interval}") keepAliveInterval: Duration,
    ): VideoTranscoder = SimulatedVideoTranscoder(processingTime, keepAliveInterval)

    @Bean fun processVideoRequestHandler(queries: VideoQueries, transcoder: VideoTranscoder, videos: VideoService) =
        ProcessVideoRequestHandler(queries, transcoder, videos)

    @Bean fun processVideoShareWorker(
        @Value("\${videos.kafka.bootstrap-servers}") bootstrapServers: String,
        @Value("\${videos.worker.group-id}") groupId: String,
        @Value("\${videos.worker.max-deliveries}") maxDeliveries: Int,
        handler: ProcessVideoRequestHandler,
        producer: KafkaProducer<String, String>,
    ) = ProcessVideoShareWorker(
        consumer = newProcessingShareConsumer(bootstrapServers, groupId),
        handler = handler,
        deadLetters = KafkaDeadLetterPublisher(producer),
        maxDeliveries = maxDeliveries,
    )
}
