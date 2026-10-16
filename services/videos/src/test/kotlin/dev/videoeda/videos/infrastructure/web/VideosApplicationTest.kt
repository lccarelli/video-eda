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
class VideosApplicationTest : VideosApplicationScenario() {
    @Test
    fun `POST videos devuelve 202 con el video en PROCESSING y GET lo consulta`() {
        val response = upload()

        assertEquals(202, response.statusCode())
        val videoId = videoIdOf(response)
        assertEquals("PROCESSING", json.readTree(response.body())["status"].asString())
        assertEquals("/videos/$videoId", response.headers().firstValue("Location").orElse(""))

        val query = send("GET", "/videos/$videoId")
        assertEquals(200, query.statusCode())
        assertEquals("PROCESSING", json.readTree(query.body())["status"].asString())
        assertEquals("Paella valenciana", json.readTree(query.body())["title"].asString())
    }

    @Test
    fun `el 202 del POST trae solo videoId y status, sin campos nulos`() {
        val response = upload()

        assertEquals(202, response.statusCode())
        assertEquals(setOf("videoId", "status"), json.readTree(response.body()).propertyNames().toSet())
    }

    @Test
    fun `PUB-08 reenviar el mismo POST con la misma Idempotency-Key devuelve el mismo video y otra clave crea otro`() {
        val key = UUID.randomUUID().toString()

        val first = upload(key)
        val replay = upload(key)
        val other = upload()

        assertEquals(videoIdOf(first), videoIdOf(replay))
        assertEquals(202, replay.statusCode())
        assertNotEquals(videoIdOf(first), videoIdOf(other))
        assertEquals(2, PostgresFixture.jdbc.sql("SELECT count(*) FROM video").query(Int::class.java).single())
    }

    @Test
    fun `sin Idempotency-Key el POST se rechaza con 400`() {
        val response = send("POST", "/videos", uploadBody())

        assertEquals(400, response.statusCode())
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/problem+json"))
    }

    @Test
    fun `PUB-10 un archivo vacio se rechaza con 422 y no escribe nada`() {
        val response = send("POST", "/videos", uploadBody(fileSizeBytes = 0), mapOf("Idempotency-Key" to UUID.randomUUID().toString()))

        assertEquals(422, response.statusCode())
        assertEquals(0, PostgresFixture.jdbc.sql("SELECT count(*) FROM outbox").query(Int::class.java).single())
    }

    @Test
    fun `un id inexistente da 404 y uno mal formado da 400`() {
        assertEquals(404, send("GET", "/videos/vid_01a13161-1208-73ad-b8df-5e1e8e69b948").statusCode())
        assertEquals(400, send("GET", "/videos/v-1").statusCode())
    }

    @Test
    fun `borrar un video que todavia no esta publicado da 409`() {
        val videoId = videoIdOf(upload())

        assertEquals(409, send("DELETE", "/videos/$videoId").statusCode())
    }
}
