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

abstract class VideosApplicationScenario {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { PostgresFixture.jdbcUrl }
            registry.add("spring.datasource.username") { PostgresFixture.username }
            registry.add("spring.datasource.password") { PostgresFixture.password }
            registry.add("videos.kafka.bootstrap-servers") { KafkaFixture.bootstrapServers }
            registry.add("videos.outbox.poll-interval") { "200ms" }

            registry.withoutProcessVideoWorker()
        }
    }

    @Value("\${local.server.port}")
    protected var port: Int = 0

    @Autowired protected lateinit var videos: VideoService

    protected val http = HttpClient.newHttpClient()
    protected val json = JsonMapper.builder().build()
    protected val creatorId = "usr_01a13161-11be-7119-8e3e-bf07f13eb833"

    @BeforeEach fun clean() = PostgresFixture.resetTables()

    protected fun send(
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

    protected fun uploadBody(fileSizeBytes: Long = 1_048_576) =
        """{"creatorId":"$creatorId","title":"Paella valenciana","fileSizeBytes":$fileSizeBytes}"""

    protected fun upload(idempotencyKey: String = UUID.randomUUID().toString(), extraHeaders: Map<String, String> = emptyMap()) =
        send("POST", "/videos", uploadBody(), mapOf("Idempotency-Key" to idempotencyKey) + extraHeaders)

    protected fun videoIdOf(response: HttpResponse<String>) = json.readTree(response.body())["videoId"].asString()
}
