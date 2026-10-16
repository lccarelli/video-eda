package dev.videoeda.videos.infrastructure

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Configuration
@EnableScheduling
class SchedulingConfiguration

@Component
class OutboxPublishingScheduler(private val publisher: OutboxPublisher) {
    private val log = LoggerFactory.getLogger(OutboxPublishingScheduler::class.java)

    @Scheduled(fixedDelayString = "\${videos.outbox.poll-interval}")
    fun publishPendingEvents() {
        try {
            publisher.publishPendingEvents()
        } catch (expectedFailure: Exception) {
            log.error("falló un ciclo del publisher del outbox; se reintenta en el próximo", expectedFailure)
        }
    }
}
