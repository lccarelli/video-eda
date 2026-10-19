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
class VideosAuthorizationTest : VideosSecurityScenario() {
    @Test
    fun `SEC-02 el creator sale del token aunque el body no lo traiga`() {
        val response = upload(TestTokens.token(TestTokens.LUCIA))

        assertEquals(202, response.statusCode())
        assertEquals(TestTokens.LUCIA, PostgresFixture.jdbc.sql("SELECT creator_id FROM video").query(String::class.java).single())
    }

    @Test
    fun `SEC-03 un creatorId igual al del token se acepta (compatibilidad)`() {
        assertEquals(202, upload(TestTokens.token(TestTokens.LUCIA), creatorId = TestTokens.LUCIA).statusCode())
    }

    @Test
    fun `SEC-04 un creatorId distinto al del token da 403 y no crea nada`() {
        val response = upload(TestTokens.token(TestTokens.LUCIA), creatorId = TestTokens.MATEO)

        isProblem(response, 403)
        assertEquals(0, count("video"))
        assertEquals(0, count("outbox"))
    }

    @Test
    fun `SEC-09 el dueño borra su video y se publica VideoDeleted`() {
        val videoId = publishedVideoOf(TestTokens.token(TestTokens.LUCIA))

        assertEquals(204, send("DELETE", "/videos/$videoId", token = TestTokens.token(TestTokens.LUCIA)).statusCode())

        val records = KafkaFixture.awaitRecordsWithKey("videos.video", videoId, expectedCount = 2)
        assertTrue(records.any { json.readTree(it.value)["type"].asString().endsWith("video-deleted") })
    }

    @Test
    fun `SEC-10 otro creator no puede borrar un video ajeno`() {
        val videoId = publishedVideoOf(TestTokens.token(TestTokens.LUCIA))

        val response = send("DELETE", "/videos/$videoId", token = TestTokens.token(TestTokens.MATEO))

        isProblem(response, 403)
        assertFalse(response.body().contains(TestTokens.LUCIA), "no revela quién es el dueño")
        assertEquals("PUBLISHED", PostgresFixture.jdbc.sql("SELECT status FROM video").query(String::class.java).single())
        val deletions = PostgresFixture.jdbc.sql(
            "SELECT count(*) FROM outbox WHERE type LIKE '%video-deleted'",
        ).query(Int::class.java).single()
        assertEquals(0, deletions)
    }

    @Test
    fun `SEC-17 una Idempotency-Key ajena da 409 sin revelar el video de otro`() {
        val key = UUID.randomUUID().toString()
        val luciasVideo = videoIdOf(upload(TestTokens.token(TestTokens.LUCIA), key = key))

        val response = upload(TestTokens.token(TestTokens.MATEO), key = key)

        isProblem(response, 409)
        assertFalse(response.body().contains(luciasVideo), "no incluye el videoId ajeno")
        assertEquals(1, count("video"))
        assertEquals(luciasVideo, videoIdOf(upload(TestTokens.token(TestTokens.LUCIA), key = key)), "su dueña sí recibe su video")
    }
}
