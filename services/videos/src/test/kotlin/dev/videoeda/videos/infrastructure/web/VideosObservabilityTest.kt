package dev.videoeda.videos.infrastructure.web

import dev.videoeda.videos.JsonSchemas
import dev.videoeda.videos.application.Topics
import dev.videoeda.videos.application.VideoService
import dev.videoeda.videos.domain.CorrelationId
import dev.videoeda.videos.domain.VideoId
import dev.videoeda.videos.infrastructure.KafkaFixture
import dev.videoeda.videos.infrastructure.PostgresFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ClosesApplicationContextWhenDone
@Import(TestJwtDecoderConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class VideosObservabilityTest : VideosApplicationScenario() {
    @Test
    fun `el X-Correlation-Id del request llega al evento publicado en Kafka y se devuelve en la respuesta`() {
        val correlationId = CorrelationId.generate().value

        val response = upload(extraHeaders = mapOf("X-Correlation-Id" to correlationId))

        assertEquals(correlationId, response.headers().firstValue("X-Correlation-Id").orElse(""))
        val videoId = videoIdOf(response)
        val record = KafkaFixture.awaitRecordsWithKey(Topics.VIDEOS_PROCESSING, videoId).single()
        assertEquals(correlationId, json.readTree(record.value)["correlationid"].asString())
        assertEquals(emptyList<String>(), JsonSchemas.errorsOf("process-video", record.value))
    }

    @Test
    fun `un X-Correlation-Id invalido o ausente se reemplaza por uno generado con formato cor_UUIDv7`() {
        val withoutHeader = upload()
        val withGarbage = upload(extraHeaders = mapOf("X-Correlation-Id" to "no-soy-un-id"))

        listOf(withoutHeader, withGarbage).forEach { response ->
            val generated = response.headers().firstValue("X-Correlation-Id").orElse("")
            assertTrue(runCatching { CorrelationId(generated) }.isSuccess, "debe ser cor_<UUIDv7>: $generated")
        }
    }

    @Test
    fun `punta a punta - se completa el procesamiento y el DELETE HTTP publica VideoPublished y VideoDeleted con sequence 1 y 2`() {
        val correlationId = CorrelationId.generate()
        val videoId = videoIdOf(upload(extraHeaders = mapOf("X-Correlation-Id" to correlationId.value)))

        videos.completeProcessing(VideoId(videoId), 184, correlationId)
        assertEquals(204, send("DELETE", "/videos/$videoId", headers = mapOf("X-Correlation-Id" to correlationId.value)).statusCode())

        val records = KafkaFixture.awaitRecordsWithKey(Topics.VIDEOS_VIDEO, videoId, expectedCount = 2)
            .sortedWith(compareBy({ it.partition }, { it.offset }))
        assertEquals(listOf(1, 2), records.map { json.readTree(it.value)["sequence"].asInt() })
        assertEquals(
            listOf("dev.videoeda.videos.video-published", "dev.videoeda.videos.video-deleted"),
            records.map { json.readTree(it.value)["type"].asString() },
        )
        assertEquals(setOf(correlationId.value), records.map { json.readTree(it.value)["correlationid"].asString() }.toSet())
    }

    @Test
    fun `los logs salen en JSON y el mismo correlationId aparece en el request y en la publicacion`(output: CapturedOutput) {
        val correlationId = CorrelationId.generate().value
        val videoId = videoIdOf(upload(extraHeaders = mapOf("X-Correlation-Id" to correlationId)))
        KafkaFixture.awaitRecordsWithKey(Topics.VIDEOS_PROCESSING, videoId)
        waitUntilThePublisherLogsTheEvent()

        val lines = output.all.lines().filter { it.startsWith("{") }.map { json.readTree(it) }
        val ofThisFlow = lines.filter { it.toString().contains(correlationId) }
        val messages = ofThisFlow.map { it["message"].asString() }
        assertTrue(lines.isNotEmpty(), "las líneas de log deben ser JSON")
        assertTrue(messages.any { it.contains("UploadVideo aceptado") }, messages.toString())
        assertTrue(messages.any { it.contains("evento publicado") }, messages.toString())
        ofThisFlow.forEach { line ->
            assertEquals(correlationId, line["correlationId"].asString(), line.toString())
        }
    }

    private fun waitUntilThePublisherLogsTheEvent() = Thread.sleep(500)

    @Test
    fun `actuator health responde UP`() {
        val response = send("GET", "/actuator/health")

        assertEquals(200, response.statusCode())
        assertEquals("UP", json.readTree(response.body())["status"].asString())
    }
}
