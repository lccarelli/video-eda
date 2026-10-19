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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ClosesApplicationContextWhenDone
@Import(TestJwtDecoderConfiguration::class)
class VideosAuthenticationTest : VideosSecurityScenario() {
    @Test
    fun `SEC-01 publicar sin token da 401 problem+json y no escribe nada`() {
        val response = upload(token = null)

        isProblem(response, 401)
        assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElse(""))
        assertEquals(0, count("video"))
        assertEquals(0, count("outbox"))
    }

    @Test
    fun `SEC-11 borrar sin token da 401`() {
        val videoId = publishedVideoOf(TestTokens.token())

        isProblem(send("DELETE", "/videos/$videoId"), 401)

        assertEquals("PUBLISHED", PostgresFixture.jdbc.sql("SELECT status FROM video").query(String::class.java).single())
    }

    @Test
    fun `SEC-05 un token vencido da 401`() {
        val response = upload(TestTokens.token(lifetime = Duration.ofMinutes(-5)))

        isProblem(response, 401)
        assertEquals("Bearer error=\"invalid_token\"", response.headers().firstValue("WWW-Authenticate").orElse(""))
        assertEquals(0, count("video"))
    }

    @Test
    fun `SEC-06 un token firmado con una clave que el emisor no publica da 401`() {
        isProblem(upload(TestTokens.token(signedWith = TestTokens.untrustedKey)), 401)
        assertEquals(0, count("video"))
    }

    @Test
    fun `SEC-07 un token de otra audiencia da 401`() {
        isProblem(upload(TestTokens.token(audience = "otra-api")), 401)
        assertEquals(0, count("video"))
    }

    @Test
    fun `un token de otro emisor da 401`() {
        isProblem(upload(TestTokens.token(issuer = "http://emisor-falso.example/realms/video-eda")), 401)
    }

    @Test
    fun `SEC-08 un token valido sin creator_id, o con uno mal formado, da 403`() {
        isProblem(upload(TestTokens.token(creatorId = null)), 403)
        isProblem(upload(TestTokens.token(creatorId = "lucia")), 403)
        assertEquals(0, count("video"))
    }
}
