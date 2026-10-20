package dev.videoeda.videos.infrastructure

import dev.videoeda.videos.application.PermanentProcessingException
import dev.videoeda.videos.application.ProcessVideoRequest
import dev.videoeda.videos.application.ProcessVideoRequestHandler
import dev.videoeda.videos.application.Topics
import org.apache.kafka.clients.consumer.AcknowledgeType
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaShareConsumer
import org.apache.kafka.common.errors.WakeupException
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

class ProcessVideoShareWorker(
    private val consumer: KafkaShareConsumer<String, String>,
    private val handler: ProcessVideoRequestHandler,
    private val deadLetters: KafkaDeadLetterPublisher,
    private val maxDeliveries: Int,
    private val topic: String = Topics.VIDEOS_PROCESSING,
    private val pollTimeout: Duration = DEFAULT_POLL_TIMEOUT,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(ProcessVideoShareWorker::class.java)
    private val stopRequested = AtomicBoolean(false)
    private var thread: Thread? = null
    private val inFlight = InFlightShareRecord(consumer, pollTimeout)

    override fun start() {
        thread = Thread(::pollUntilStopped, "videos-processing-worker").also { it.start() }
    }

    override fun stop() {
        stopRequested.set(true)
        consumer.wakeup()
        thread?.join(STOP_TIMEOUT_MILLIS)
        thread = null
    }

    override fun isRunning() = thread?.isAlive == true

    private fun pollUntilStopped() {
        consumer.use {
            consumer.subscribe(listOf(topic))
            log.info("worker de procesamiento escuchando {} (máximo {} entregas por mensaje)", topic, maxDeliveries)
            while (!stopRequested.get()) pollOnce()
        }
    }

    private fun pollOnce() {
        try {
            consumer.poll(pollTimeout).forEach(::process)
        } catch (expectedWakeup: WakeupException) {
            return
        } catch (expectedFailure: Exception) {
            log.error("falló un ciclo del worker; el mensaje en curso se reentrega al vencer su lock", expectedFailure)
            Thread.sleep(PAUSE_AFTER_FAILED_CYCLE_MILLIS)
        }
    }

    private fun process(record: ConsumerRecord<String, String>) {
        inFlight.hold(record)
        try {
            withLogContext("aggregateId" to record.key()) { handleMessage(record) }
        } finally {
            inFlight.drop()
        }
    }

    private fun handleMessage(record: ConsumerRecord<String, String>) {
        val attempt = record.deliveryCount().map { it.toInt() }.orElse(1)
        val request = try {
            parseProcessVideo(record.value())
        } catch (invalid: PermanentProcessingException) {
            giveUp(record, null, attempt, "mensaje inválido: ${invalid.message}")
            return
        }
        withLogContext("correlationId" to request.correlationId.value, "eventId" to request.eventId.value) {
            handle(record, request, attempt)
        }
    }

    private fun handle(record: ConsumerRecord<String, String>, request: ProcessVideoRequest, attempt: Int) {
        try {
            handler.handle(request, keepAlive = inFlight::renew)
        } catch (expectedWakeup: WakeupException) {
            throw expectedWakeup
        } catch (permanent: PermanentProcessingException) {
            giveUp(record, request, attempt, "falla permanente: ${permanent.message}")
            return
        } catch (expectedFailure: Exception) {
            if (attempt >= maxDeliveries) {
                giveUp(record, request, attempt, "entregas agotadas ($maxDeliveries); última falla: ${expectedFailure.message}")
            } else {
                log.warn("ProcessVideo falló en la entrega {} de {}: se libera para reintentar", attempt, maxDeliveries, expectedFailure)
                inFlight.acknowledge(AcknowledgeType.RELEASE)
            }
            return
        }
        log.info("ProcessVideo procesado (entrega {})", attempt)
        inFlight.acknowledge(AcknowledgeType.ACCEPT)
    }

    private fun giveUp(record: ConsumerRecord<String, String>, request: ProcessVideoRequest?, attempt: Int, cause: String) {
        val caseClosed = try {
            deadLetters.publish(record, cause, attempt, request?.eventId?.value)
            request?.let { handler.giveUp(it, cause) }
            true
        } catch (expectedWakeup: WakeupException) {
            throw expectedWakeup
        } catch (expectedFailure: Exception) {
            log.error("no se pudo cerrar el caso en la DLQ ({}): se libera para reintentar", cause, expectedFailure)
            false
        }
        if (caseClosed) {
            withLogContext("alert" to "true") {
                log.error("ALERTA: ProcessVideo rendido tras {} entrega(s) y enviado a la DLQ: {}", attempt, cause)
            }
            inFlight.acknowledge(AcknowledgeType.REJECT)
        } else {
            inFlight.acknowledge(AcknowledgeType.RELEASE)
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 15_000L
        private const val PAUSE_AFTER_FAILED_CYCLE_MILLIS = 1_000L
        private val DEFAULT_POLL_TIMEOUT: Duration = Duration.ofMillis(500)
    }
}
