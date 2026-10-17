package dev.videoeda.videos.infrastructure.web

import dev.videoeda.videos.JsonSchemas
import dev.videoeda.videos.application.Topics
import dev.videoeda.videos.infrastructure.KafkaDeadLetterPublisher
import dev.videoeda.videos.infrastructure.KafkaFixture
import dev.videoeda.videos.infrastructure.PostgresFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
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
class VideoProcessingApplicationTest {
    companion object {
        private val workerGroupId = "videos-worker-e2e-${UUID.randomUUID().toString().take(8)}"

        init {
            KafkaFixture.configureShareGroup(workerGroupId, recordLockDurationMillis = 30_000)
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { PostgresFixture.jdbcUrl }
            registry.add("spring.datasource.username") { PostgresFixture.username }
            registry.add("spring.datasource.password") { PostgresFixture.password }
            registry.add("videos.kafka.bootstrap-servers") { KafkaFixture.bootstrapServers }
            registry.add("videos.outbox.poll-interval") { "200ms" }
            registry.add("videos.worker.enabled") { "true" }
            registry.add("videos.worker.group-id") { workerGroupId }
            registry.add("videos.worker.processing-time") { "300ms" }
            registry.add("videos.worker.keep-alive-interval") { "10s" }
        }
    }

    @Value("\${local.server.port}")
    private var port: Int = 0

    private val http = HttpClient.newHttpClient()
    private val json = JsonMapper.builder().build()
    private val creatorId = "usr_01a13161-11be-7119-8e3e-bf07f13eb833"

    @BeforeEach fun clean() = PostgresFixture.resetTables()

    private fun send(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        token: String? = TestTokens.token(),
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        if (body != null) builder.header("Content-Type", "application/json")
        headers.forEach { (name, value) -> builder.header(name, value) }
        token?.let { builder.header("Authorization", "Bearer $it") }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun upload(title: String, correlationId: String? = null): String {
        val headers = mapOf("Idempotency-Key" to UUID.randomUUID().toString()) +
            (correlationId?.let { mapOf("X-Correlation-Id" to it) } ?: emptyMap())
        val response = send("POST", "/videos", """{"creatorId":"$creatorId","title":"$title","fileSizeBytes":1048576}""", headers)
        assertEquals(202, response.statusCode())
        return json.readTree(response.body())["videoId"].asString()
    }

    private fun statusOf(videoId: String) = json.readTree(send("GET", "/videos/$videoId").body())["status"].asString()

    private fun awaitStatus(videoId: String, expected: String, timeoutSeconds: Long = 40) {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1_000
        while (statusOf(videoId) != expected) {
            check(System.currentTimeMillis() < deadline) {
                "el video $videoId no llegó a $expected en $timeoutSeconds s (está en ${statusOf(videoId)})"
            }
            Thread.sleep(200)
        }
    }

    @Test
    fun `PUB-01 subir un video lo lleva hasta PUBLISHED y VideoPublished sale por videos_video con el mismo correlationId`() {
        val correlationId = "cor_01a13161-13c4-7752-b78c-4c2b8c8039fb"

        val videoId = upload("Paella valenciana", correlationId)

        awaitStatus(videoId, "PUBLISHED")
        val published = KafkaFixture.awaitRecordsWithKey(Topics.VIDEOS_VIDEO, videoId).single()
        assertEquals(emptyList<String>(), JsonSchemas.errorsOf("video-published", published.value))
        val event = json.readTree(published.value)
        assertEquals("dev.videoeda.videos.video-published", event["type"].asString())
        assertEquals(correlationId, event["correlationid"].asString(), "el correlationId del POST llega hasta VideoPublished")
        assertEquals(184, event["data"]["durationSeconds"].asInt())
    }

    @Test
    fun `PUB-07 con la marca falla-transitoria el worker reintenta y el video termina PUBLISHED`() {
        val videoId = upload("Paella [falla-transitoria]")

        awaitStatus(videoId, "PUBLISHED")
        assertEquals(1, KafkaFixture.awaitRecordsWithKey(Topics.VIDEOS_VIDEO, videoId).size)
    }

    @Test
    fun `PUB-04 con la marca falla-permanente el video termina FAILED, no se publica y el mensaje queda en la DLQ`() {
        val videoId = upload("Paella [falla-permanente]")

        awaitStatus(videoId, "FAILED")
        val deadLetter = KafkaFixture.awaitRecordsWithKey(Topics.VIDEOS_PROCESSING_DLQ, videoId).single()
        assertTrue(deadLetter.headers.getValue(KafkaDeadLetterPublisher.CAUSE_HEADER).contains("falla permanente"))
        assertEquals("1", deadLetter.headers[KafkaDeadLetterPublisher.ATTEMPTS_HEADER])
        assertTrue(KafkaFixture.readAllRecords(Topics.VIDEOS_VIDEO).none { it.key == videoId }, "no se emite VideoPublished")
    }
}
