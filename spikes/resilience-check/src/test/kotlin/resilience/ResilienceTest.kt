package resilience

import org.apache.kafka.clients.producer.KafkaProducer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ResilienceTest {
    private val producers = mutableListOf<KafkaProducer<String, String>>()

    @BeforeEach fun clean() = PostgresFixture.resetTables()
    @AfterEach fun closeProducers() = producers.forEach { it.close() }

    private fun newProducer() = KafkaFixture.createIdempotentProducer().also { producers += it }

    private class Scenario(val topic: String, val deadLetterTopic: String, val groupId: String)

    private fun newScenario(): Scenario {
        val suffix = UUID.randomUUID().toString().take(8)
        val scenario = Scenario("interactions.comment-$suffix", "interactions.dlq-$suffix", "comment-copy-$suffix")
        KafkaFixture.createTopic(scenario.topic)
        KafkaFixture.createTopic(scenario.deadLetterTopic)
        return scenario
    }

    private fun handler(guard: SequenceGuard = SequenceGuard.STRICT, isDependencyAvailable: () -> Boolean = { true }) =
        CommentCopyHandler(PostgresFixture.dataSource, guard, isDependencyAvailable)

    private fun consumer(
        scenario: Scenario,
        handler: CommentCopyHandler,
        settings: ConsumerSettings = ConsumerSettings(),
    ) = CommentCopyConsumer(
        KafkaFixture.bootstrapServers, scenario.topic, scenario.deadLetterTopic, scenario.groupId,
        handler, newProducer(), settings,
    )

    private fun redrive(scenario: Scenario, handler: CommentCopyHandler) =
        DeadLetterRedrive(KafkaFixture.bootstrapServers, scenario.deadLetterTopic, handler).redrive()

    private fun send(scenario: Scenario, producer: KafkaProducer<String, String>, commentId: String, json: String) =
        KafkaFixture.send(producer, scenario.topic, commentId, json)

    private class ParkedScenario(
        val scenario: Scenario,
        val parkedCommentId: String,
        val parkedVideoId: String,
        val healthyCommentId: String,
    )

    private fun produceParkedScenario(): ParkedScenario {
        val scenario = newScenario()
        val parked = ParkedScenario(scenario, newId("cmt"), newId("vid"), newId("cmt"))
        val knownVideoId = newId("vid")
        PostgresFixture.markVideoAsKnown(knownVideoId)
        val producer = newProducer()
        send(scenario, producer, parked.parkedCommentId, CommentCloudEvents.posted(parked.parkedCommentId, parked.parkedVideoId, "Gracias"))
        send(scenario, producer, parked.healthyCommentId, CommentCloudEvents.posted(parked.healthyCommentId, knownVideoId, "Hola"))
        send(scenario, producer, parked.parkedCommentId, CommentCloudEvents.edited(parked.parkedCommentId, 2, "Gracias, quedó riquísima"))
        send(scenario, producer, parked.healthyCommentId, CommentCloudEvents.edited(parked.healthyCommentId, 2, "Hola, buenísimo"))
        return parked
    }

    private val fastRetries = ConsumerSettings(maxAttempts = 3, baseBackoffMillis = 10)

    @Test fun `RES-01 el fallido y el siguiente de su key van a la DLQ en orden y las demas keys siguen`() {
        val parked = produceParkedScenario()
        val summary = consumer(parked.scenario, handler(), fastRetries).processUntilIdle()

        assertEquals(Pair("Hola, buenísimo", "ACTIVE"), PostgresFixture.commentCopy(parked.healthyCommentId))
        assertNull(PostgresFixture.commentCopy(parked.parkedCommentId), "el comentario apartado no existe todavía")
        assertEquals(ProcessingSummary(applied = 2, discarded = 0, deadLettered = 2, groupGenerationIds = summary.groupGenerationIds), summary)

        val deadLetters = KafkaFixture.readAllRecords(parked.scenario.deadLetterTopic)
        assertEquals(2, deadLetters.size)
        assertEquals(setOf(parked.parkedCommentId), deadLetters.map { it.key }.toSet(), "la DLQ conserva la key original")
        assertEquals(1, deadLetters.map { it.partition }.toSet().size, "ambos en la misma partición de la DLQ")
        assertEquals(listOf("RETRIES_EXHAUSTED", "SEQUENCE_GAP"), deadLetters.map { it.headers.getValue("dlt-cause") })
        assertEquals(listOf("3", "1"), deadLetters.map { it.headers.getValue("dlt-attempts") })
        val originalOffsets = deadLetters.map { it.headers.getValue("dlt-original-offset").toLong() }
        assertEquals(originalOffsets.sorted(), originalOffsets, "orden de origen conservado dentro de la DLQ")
        assertEquals(2, PostgresFixture.countRows("inbox"), "solo los 2 eventos aplicados del comentario sano; los apartados no se registran")
        assertEquals(0L, KafkaFixture.lagOf(parked.scenario.groupId, parked.scenario.topic), "el consumer avanzó: lag cero")
        Evidence.write(
            "RES-01-dlq.txt",
            deadLetters.joinToString("\n") { "partition=${it.partition} offset=${it.offset} key=${it.key} headers=${it.headers}" },
        )
    }

    @Test fun `RES-02 el redrive aplica el CommentPosted y despues el CommentEdited y el texto final es el de la edicion`() {
        val parked = produceParkedScenario()
        consumer(parked.scenario, handler(), fastRetries).processUntilIdle()
        PostgresFixture.markVideoAsKnown(parked.parkedVideoId)

        val summary = redrive(parked.scenario, handler())

        assertEquals(RedriveSummary(applied = 2, discarded = 0, stillPending = 0), summary)
        assertEquals(Pair("Gracias, quedó riquísima", "ACTIVE"), PostgresFixture.commentCopy(parked.parkedCommentId))
        assertEquals(2, PostgresFixture.query("SELECT last_sequence FROM stream_position WHERE stream_id = '${parked.parkedCommentId}'") { it.getInt(1) }.single())
    }

    @Test fun `RES-03 repetir el redrive descarta lo ya aplicado por el inbox`() {
        val parked = produceParkedScenario()
        consumer(parked.scenario, handler(), fastRetries).processUntilIdle()
        PostgresFixture.markVideoAsKnown(parked.parkedVideoId)
        redrive(parked.scenario, handler())

        val secondRedrive = redrive(parked.scenario, handler())

        assertEquals(RedriveSummary(applied = 0, discarded = 2, stillPending = 0), secondRedrive)
        assertEquals(Pair("Gracias, quedó riquísima", "ACTIVE"), PostgresFixture.commentCopy(parked.parkedCommentId))
    }

    @Test fun `RES-03 b - si la causa no se corrigio el redrive deja todo pendiente y no pierde nada`() {
        val parked = produceParkedScenario()
        consumer(parked.scenario, handler(), fastRetries).processUntilIdle()

        val summary = redrive(parked.scenario, handler())

        assertEquals(RedriveSummary(applied = 0, discarded = 0, stillPending = 2), summary)
        assertNull(PostgresFixture.commentCopy(parked.parkedCommentId))
    }

    @Test fun `CASO QUE FALLA - guard de la nota 03 que solo descarta lo viejo - el comentario se pierde por completo`() {
        val parked = produceParkedScenario()
        val oldGuard = SequenceGuard.DISCARD_OLD_ONLY
        consumer(parked.scenario, handler(oldGuard), fastRetries).processUntilIdle()
        PostgresFixture.markVideoAsKnown(parked.parkedVideoId)

        val summary = redrive(parked.scenario, handler(oldGuard))

        Evidence.write("falla-guard-nota-03.txt", "redrive=$summary copia=${PostgresFixture.commentCopy(parked.parkedCommentId)}")
        assertNull(PostgresFixture.commentCopy(parked.parkedCommentId), "el CommentPosted se descartó por viejo: el comentario nunca existe")
        assertEquals(1, summary.discarded)
    }

    @Test fun `CASO QUE FALLA - sin guard - el comentario aparece con el texto viejo y la edicion se pierde`() {
        val parked = produceParkedScenario()
        val noGuard = SequenceGuard.NONE
        consumer(parked.scenario, handler(noGuard), fastRetries).processUntilIdle()
        PostgresFixture.markVideoAsKnown(parked.parkedVideoId)

        val summary = redrive(parked.scenario, handler(noGuard))

        Evidence.write("falla-sin-guard.txt", "redrive=$summary copia=${PostgresFixture.commentCopy(parked.parkedCommentId)}")
        assertEquals(Pair("Gracias", "ACTIVE"), PostgresFixture.commentCopy(parked.parkedCommentId), "texto viejo: la edición se perdió sin error")
    }

    @Test fun `RES-04 un mensaje con schema invalido va directo a la DLQ sin reintentos y el siguiente se procesa`() {
        val scenario = newScenario()
        val producer = newProducer()
        val poisonedKey = newId("cmt")
        val healthyCommentId = newId("cmt")
        val knownVideoId = newId("vid")
        PostgresFixture.markVideoAsKnown(knownVideoId)
        send(scenario, producer, poisonedKey, """{"specversion":"1.0","id":"evt_sin_sequence"}""")
        send(scenario, producer, healthyCommentId, CommentCloudEvents.posted(healthyCommentId, knownVideoId, "Hola"))

        val summary = consumer(scenario, handler(), fastRetries).processUntilIdle()

        assertEquals(1, summary.applied)
        assertEquals(1, summary.deadLettered)
        val deadLetter = KafkaFixture.readAllRecords(scenario.deadLetterTopic).single()
        assertEquals("PERMANENT_INVALID_EVENT", deadLetter.headers.getValue("dlt-cause"))
        assertEquals("1", deadLetter.headers.getValue("dlt-attempts"), "error permanente: sin reintentos")
        assertEquals(poisonedKey, deadLetter.key)
    }

    @Test fun `RES-05 el proveedor caido pausa las particiones, no manda nada a la DLQ y el miembro sigue en el grupo`() {
        val scenario = newScenario()
        val commentId = newId("cmt")
        val videoId = newId("vid")
        PostgresFixture.markVideoAsKnown(videoId)
        send(scenario, newProducer(), commentId, CommentCloudEvents.posted(commentId, videoId, "Hola"))
        val providerIsUp = AtomicBoolean(false)
        Thread { Thread.sleep(5_000); providerIsUp.set(true) }.start()
        val settings = ConsumerSettings(maxPollIntervalMillis = 3_000, keepAliveWhileWaitingForDependency = true)

        val summary = consumer(scenario, handler(isDependencyAvailable = providerIsUp::get), settings).processUntilIdle()

        Evidence.write("RES-05-con-pausa.txt", "summary=$summary")
        assertEquals(1, summary.applied)
        assertEquals(0, summary.deadLettered)
        assertEquals(1, summary.groupGenerationIds.size, "no hubo rebalance: la generación del grupo no cambió")
        assertEquals(Pair("Hola", "ACTIVE"), PostgresFixture.commentCopy(commentId))
        assertEquals(0L, KafkaFixture.depthOf(scenario.deadLetterTopic))
    }

    @Test fun `RES-05 b - contrafactual - esperar sin llamar a poll supera max_poll_interval y el miembro es expulsado`() {
        val scenario = newScenario()
        val commentId = newId("cmt")
        val videoId = newId("vid")
        PostgresFixture.markVideoAsKnown(videoId)
        send(scenario, newProducer(), commentId, CommentCloudEvents.posted(commentId, videoId, "Hola"))
        val providerIsUp = AtomicBoolean(false)
        Thread { Thread.sleep(5_000); providerIsUp.set(true) }.start()
        val settings = ConsumerSettings(maxPollIntervalMillis = 3_000, keepAliveWhileWaitingForDependency = false)

        val firstRun = runCatching { consumer(scenario, handler(isDependencyAvailable = providerIsUp::get), settings).processUntilIdle() }
        val secondRun = consumer(scenario, handler(), ConsumerSettings()).processUntilIdle()

        Evidence.write(
            "RES-05-contrafactual.txt",
            "primera corrida: ${firstRun.exceptionOrNull()?.javaClass?.name ?: firstRun.getOrNull()}\nsegunda corrida: $secondRun",
        )
        val wasEvicted = firstRun.exceptionOrNull() != null || (firstRun.getOrNull()?.groupGenerationIds?.size ?: 0) > 1
        assertTrue(wasEvicted, "sin poll durante la espera el miembro debería perder la partición")
        assertEquals(Pair("Hola", "ACTIVE"), PostgresFixture.commentCopy(commentId))
        assertEquals(1, PostgresFixture.countRows("comment_copy"), "el efecto se aplicó una sola vez aunque el offset se reentregó")
    }

    @Test fun `RES-07 cincuenta comentarios con diez videos desconocidos terminan todos consistentes tras el redrive`() {
        val scenario = newScenario()
        val producer = newProducer()
        val commentIds = (0 until 50).map { newId("cmt") }
        val videoIds = (0 until 50).map { if (it % 5 == 0) newId("vid") else newId("vid").also(PostgresFixture::markVideoAsKnown) }
        commentIds.indices.forEach { send(scenario, producer, commentIds[it], CommentCloudEvents.posted(commentIds[it], videoIds[it], "original $it")) }
        commentIds.indices.forEach { send(scenario, producer, commentIds[it], CommentCloudEvents.edited(commentIds[it], 2, "editado $it")) }

        val summary = consumer(scenario, handler(), ConsumerSettings(maxAttempts = 2, baseBackoffMillis = 5)).processUntilIdle()

        assertEquals(80, summary.applied, "40 comentarios sanos x 2 eventos")
        assertEquals(20, summary.deadLettered, "10 comentarios apartados x 2 eventos")
        val deadLetters = KafkaFixture.readAllRecords(scenario.deadLetterTopic)
        deadLetters.groupBy { it.key }.forEach { (key, records) ->
            val sequences = records.sortedWith(compareBy({ it.partition }, { it.offset })).map { CloudEventMessage.parse(it.value).sequence }
            assertEquals(listOf(1, 2), sequences, "orden por key en la DLQ: $key")
        }
        (0 until 50).filter { it % 5 == 0 }.forEach { PostgresFixture.markVideoAsKnown(videoIds[it]) }

        val redriveSummary = redrive(scenario, handler())

        assertEquals(RedriveSummary(applied = 20, discarded = 0, stillPending = 0), redriveSummary)
        commentIds.indices.forEach { assertEquals(Pair("editado $it", "ACTIVE"), PostgresFixture.commentCopy(commentIds[it]), "comentario $it") }
        assertEquals(0L, KafkaFixture.lagOf(scenario.groupId, scenario.topic))
    }

    @Test fun `una caida antes de confirmar el offset duplica las entradas de la DLQ y el redrive aplica cada evento una vez`() {
        val parked = produceParkedScenario()
        val sharedHandler = handler()
        assertTrue(runCatching { consumer(parked.scenario, sharedHandler, fastRetries).processUntilIdle(crashBeforeCommit = true) }.exceptionOrNull() is SimulatedCrash)
        consumer(parked.scenario, sharedHandler, fastRetries).processUntilIdle()

        val deadLetters = KafkaFixture.readAllRecords(parked.scenario.deadLetterTopic)
        val distinctEventIds = deadLetters.map { CloudEventMessage.parse(it.value).eventId }.toSet()
        assertEquals(4, deadLetters.size, "cada evento apartado quedó dos veces")
        assertEquals(2, distinctEventIds.size, "pero con el mismo event_id")

        PostgresFixture.markVideoAsKnown(parked.parkedVideoId)
        val summary = redrive(parked.scenario, handler())

        assertEquals(RedriveSummary(applied = 2, discarded = 2, stillPending = 0), summary)
        assertEquals(Pair("Gracias, quedó riquísima", "ACTIVE"), PostgresFixture.commentCopy(parked.parkedCommentId))
    }

    @Test fun `una inundacion donde todo falla deja el consumer al dia y cada evento en la DLQ`() {
        val scenario = newScenario()
        val producer = newProducer()
        val producedEventIds = mutableSetOf<String>()
        repeat(100) {
            val commentId = newId("cmt")
            val posted = CommentCloudEvents.posted(commentId, newId("vid"), "x")
            val edited = CommentCloudEvents.edited(commentId, 2, "y")
            producedEventIds += CloudEventMessage.parse(posted).eventId
            producedEventIds += CloudEventMessage.parse(edited).eventId
            send(scenario, producer, commentId, posted)
            send(scenario, producer, commentId, edited)
        }

        val summary = consumer(scenario, handler(), ConsumerSettings(maxAttempts = 2, baseBackoffMillis = 5)).processUntilIdle()

        assertEquals(0, summary.applied)
        assertEquals(200, summary.deadLettered)
        assertEquals(200L, KafkaFixture.depthOf(scenario.deadLetterTopic), "la profundidad de la DLQ se mide por offsets finales")
        assertEquals(0L, KafkaFixture.lagOf(scenario.groupId, scenario.topic))
        val deadLetteredEventIds = KafkaFixture.readAllRecords(scenario.deadLetterTopic).map { CloudEventMessage.parse(it.value).eventId }.toSet()
        assertEquals(producedEventIds, deadLetteredEventIds, "no se perdió ningún evento")
    }
}
