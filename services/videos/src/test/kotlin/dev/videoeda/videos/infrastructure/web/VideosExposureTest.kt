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
class VideosExposureTest : VideosSecurityScenario() {
    @Test
    fun `SEC-12 mirar un video es publico`() {
        val videoId = videoIdOf(upload(TestTokens.token()))

        assertEquals(200, send("GET", "/videos/$videoId").statusCode())
    }

    @Test
    fun `SEC-13 el health check es publico`() {
        assertEquals(200, send("GET", "/actuator/health").statusCode())
    }

    @Test
    fun `SEC-14 lo no declarado se deniega y no expone configuracion`() {
        val anonymous = send("GET", "/actuator/env")
        val authenticated = send("GET", "/actuator/env", token = TestTokens.token())

        isProblem(anonymous, 401)
        isProblem(authenticated, 403)
        listOf(anonymous, authenticated).forEach { assertFalse(it.body().contains("propertySources")) }
        isProblem(send("GET", "/no-existe"), 401)
    }

    @Test
    fun `SEC-15 los errores de seguridad no filtran detalles internos`() {
        val bodies = listOf(
            upload(token = null),
            upload(TestTokens.token(lifetime = Duration.ofMinutes(-5))),
            upload(TestTokens.token(creatorId = null)),
        ).map { it.body() }

        bodies.forEach { body ->
            assertEquals(setOf("title", "status", "detail", "instance"), json.readTree(body).propertyNames().toSet(), body)
            listOf("Exception", "org.springframework", "eyJ", "Jwt", "expired", "stack").forEach { leak ->
                assertFalse(body.contains(leak), "el cuerpo no debe contener '$leak': $body")
            }
        }
    }

    @Test
    fun `SEC-16 las respuestas llevan cabeceras de seguridad, tambien las de error`() {
        val ok = send("GET", "/actuator/health")
        val rejected = upload(token = null)

        listOf(ok, rejected).forEach { response ->
            val headers = response.headers()
            assertEquals("nosniff", headers.firstValue("X-Content-Type-Options").orElse(""))
            assertTrue(headers.firstValue("Cache-Control").orElse("").contains("no-store"))
            assertEquals("default-src 'none'; frame-ancestors 'none'", headers.firstValue("Content-Security-Policy").orElse(""))
            assertEquals("no-referrer", headers.firstValue("Referrer-Policy").orElse(""))
        }
    }
}
