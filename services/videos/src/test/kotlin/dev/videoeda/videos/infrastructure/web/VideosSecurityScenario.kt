package dev.videoeda.videos.infrastructure.web

import dev.videoeda.videos.application.VideoService
import dev.videoeda.videos.domain.CorrelationId
import dev.videoeda.videos.domain.VideoId
import dev.videoeda.videos.infrastructure.KafkaFixture
import dev.videoeda.videos.infrastructure.PostgresFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
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
import java.time.Duration
import java.util.UUID

abstract class VideosSecurityScenario {
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

    @BeforeEach fun clean() = PostgresFixture.resetTables()

    protected fun send(
        method: String,
        path: String,
        body: String? = null,
        token: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        if (body != null) builder.header("Content-Type", "application/json")
        headers.forEach { (name, value) -> builder.header(name, value) }
        token?.let { builder.header("Authorization", "Bearer $it") }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    protected fun uploadBody(creatorId: String? = null) =
        """{${creatorId?.let { "\"creatorId\":\"$it\"," } ?: ""}"title":"Paella valenciana","fileSizeBytes":1048576}"""

    protected fun upload(token: String?, creatorId: String? = null, key: String = UUID.randomUUID().toString()) =
        send("POST", "/videos", uploadBody(creatorId), token, mapOf("Idempotency-Key" to key))

    protected fun videoIdOf(response: HttpResponse<String>) = json.readTree(response.body())["videoId"].asString()

    protected fun count(table: String) = PostgresFixture.jdbc.sql("SELECT count(*) FROM $table").query(Int::class.java).single()

    protected fun isProblem(response: HttpResponse<String>, status: Int) {
        assertEquals(status, response.statusCode(), response.body())
        val contentType = response.headers().firstValue("Content-Type").orElse("")
        assertTrue(contentType.contains("application/problem+json"), response.headers().toString())
        assertEquals(status, json.readTree(response.body())["status"].asInt())
    }

    protected fun publishedVideoOf(creatorToken: String): String {
        val videoId = videoIdOf(upload(creatorToken))
        videos.completeProcessing(VideoId(videoId), 184, CorrelationId.generate())
        return videoId
    }
}
