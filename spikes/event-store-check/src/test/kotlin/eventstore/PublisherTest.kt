package eventstore

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PublisherTest {
    private val store = CommentEventStore(PostgresFixture.dataSource)
    private val videoId = "vid_01a13161-1208-73ad-b8df-5e1e8e69b948"
    private val authorId = "usr_01a13161-112a-7681-b280-02ac0205cd15"
    private val producers = mutableListOf<org.apache.kafka.clients.producer.KafkaProducer<String, String>>()

    @BeforeEach fun clean() = PostgresFixture.resetTables()
    @AfterEach fun close() = producers.forEach { it.close() }

    private fun newPublisher(topic: String, rowLocking: RowLocking = RowLocking.NONE) =
        CommentEventPublisher(PostgresFixture.dataSource, KafkaFixture.createIdempotentProducer().also { producers += it }, topic, rowLocking)

    private fun post(text: String = "Muy bueno"): String = PrefixedIds.generate("cmt").also { store.postComment(it, videoId, authorId, text) }

    private fun eventIdOf(value: String) = jsonMapper.readTree(value)["id"].asText()
    private fun versionOf(value: String) = jsonMapper.readTree(value)["sequence"].asInt()

    private fun consumeWithInbox(topic: String): InboxOutcome {
        val consumedRecords = KafkaFixture.readAllRecords(topic)
        var appliedCount = 0
        var droppedAsDuplicateCount = 0
        for (record in consumedRecords) {
            val cloudEvent = jsonMapper.readTree(record.value)
            PostgresFixture.dataSource.connection.use { connection ->
                connection.autoCommit = false
                val insertedInInbox = connection
                    .prepareStatement("INSERT INTO inbox (source, id) VALUES (?, ?) ON CONFLICT DO NOTHING")
                    .use { statement ->
                        statement.setString(1, cloudEvent["source"].asText())
                        statement.setString(2, cloudEvent["id"].asText())
                        statement.executeUpdate()
                    }
                if (insertedInInbox == 1) {
                    connection.prepareStatement("INSERT INTO notification_effect (event_id) VALUES (?)").use { statement ->
                        statement.setString(1, cloudEvent["id"].asText())
                        statement.executeUpdate()
                    }
                    appliedCount++
                } else {
                    droppedAsDuplicateCount++
                }
                connection.commit()
            }
        }
        return InboxOutcome(consumedRecords.size, appliedCount, droppedAsDuplicateCount)
    }

    private data class InboxOutcome(val readCount: Int, val appliedCount: Int, val droppedAsDuplicateCount: Int)

    @Test fun `publisher - lee por global_seq, publica con key = stream_id y marca published_at`() {
        val topic = KafkaFixture.createUniqueTopic()
        val firstCommentId = post("a")
        val secondCommentId = post("b")
        store.editComment(firstCommentId, "a2")
        store.editComment(secondCommentId, "b2")
        store.deleteComment(firstCommentId)
        val commentPublisher = newPublisher(topic)
        assertEquals(5, commentPublisher.publishPendingEvents())
        assertEquals(0, PostgresFixture.countRows("comment_events WHERE published_at IS NULL"))
        assertEquals(0, commentPublisher.publishPendingEvents(), "ya no queda nada por publicar")
        val records = KafkaFixture.readAllRecords(topic)
        assertEquals(5, records.size)
        for (record in records) assertEquals(jsonMapper.readTree(record.value)["subject"].asText(), record.key)
        for (key in listOf(firstCommentId, secondCommentId)) {
            val versions = records.filter { it.key == key }.sortedWith(compareBy({ it.partition }, { it.offset })).map { versionOf(it.value) }
            assertEquals(versions.sorted(), versions, "versiones de $key en el orden del log")
        }

        assertEquals(1, records.filter { it.key == firstCommentId }.map { it.partition }.toSet().size)
        File("build/evidence").mkdirs()
        File("build/evidence/captured-events.jsonl").writeText(records.joinToString("\n") { it.value } + "\n")
    }

    @Test fun `ES-08 caida entre publicar y marcar - el mismo event_id sale dos veces y el inbox aplica el efecto una vez`() {
        val topic = KafkaFixture.createUniqueTopic()
        val commentId = post()
        val eventId = store.readStreamEvents(commentId).single().eventId
        val commentPublisher = newPublisher(topic)

        assertThrows(SimulatedCrash::class.java) { commentPublisher.publishPendingEvents(simulateCrashAfterFirstSend = true) }
        assertNull(PostgresFixture.query("SELECT published_at FROM comment_events") { it.getTimestamp(1) }.single())
        assertEquals(1, KafkaFixture.readAllRecords(topic).size, "el broker ya tiene el evento")

        assertEquals(1, newPublisher(topic).publishPendingEvents())
        assertNotNull(PostgresFixture.query("SELECT published_at FROM comment_events") { it.getTimestamp(1) }.single())

        val records = KafkaFixture.readAllRecords(topic)
        assertEquals(2, records.size, "salió dos veces")
        assertEquals(setOf(eventId), records.map { eventIdOf(it.value) }.toSet(), "con el MISMO event_id")
        assertEquals(setOf(commentId), records.map { it.key }.toSet())

        val outcome = consumeWithInbox(topic)
        assertEquals(2, outcome.readCount)
        assertEquals(1, outcome.appliedCount)
        assertEquals(1, outcome.droppedAsDuplicateCount)
        assertEquals(1, PostgresFixture.countRows("notification_effect"))
        assertEquals(1, PostgresFixture.countRows("inbox"))
    }

    @Test fun `dos instancias PLAIN a la vez - publican las mismas filas dos veces`() {
        val topic = KafkaFixture.createUniqueTopic()
        repeat(50) { post("c$it") }
        val publisherA = newPublisher(topic)
        val publisherB = newPublisher(topic)
        val publisherAHasReadRows = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(1)
        val publicationOfA = executor.submit<Int> { publisherA.publishPendingEvents(afterReadingPendingEvents = { publisherAHasReadRows.countDown(); Thread.sleep(2000) }) }
        assertTrue(publisherAHasReadRows.await(10, TimeUnit.SECONDS))
        val publishedByB = publisherB.publishPendingEvents()
        val publishedByA = publicationOfA.get(30, TimeUnit.SECONDS)
        executor.shutdown()
        val records = KafkaFixture.readAllRecords(topic)
        val distinctEventIds = records.map { eventIdOf(it.value) }.toSet().size
        val text = "B envió $publishedByB, A envió $publishedByA, registros en el topic: ${records.size}, event_id distintos: $distinctEventIds"
        Evidence.write("dos-instancias-PLAIN.txt", text)
        assertEquals(50, publishedByB); assertEquals(50, publishedByA)
        assertEquals(100, records.size); assertEquals(50, distinctEventIds)
    }

    @Test fun `dos instancias SKIP LOCKED - sin duplicados pero ROMPEN el orden por key`() {
        val topic = KafkaFixture.createUniqueTopic()
        val watchedCommentId = post("x1")
        repeat(29) { post("relleno $it") }
        store.editComment(watchedCommentId, "x2")
        val publisherA = newPublisher(topic, RowLocking.SKIP_LOCKED)
        val publisherB = newPublisher(topic, RowLocking.SKIP_LOCKED)
        val publisherAHasLockedRows = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(1)
        val publicationOfA = executor.submit<Int> { publisherA.publishPendingEvents(batchSize = 25, afterReadingPendingEvents = { publisherAHasLockedRows.countDown(); Thread.sleep(2500) }) }
        assertTrue(publisherAHasLockedRows.await(10, TimeUnit.SECONDS))
        val publishedByB = publisherB.publishPendingEvents(batchSize = 100)
        val publishedByA = publicationOfA.get(30, TimeUnit.SECONDS)
        executor.shutdown()
        val records = KafkaFixture.readAllRecords(topic)
        val distinctEventIds = records.map { eventIdOf(it.value) }.toSet().size
        val watchedCommentVersionOrder = records.filter { it.key == watchedCommentId }.sortedWith(compareBy({ it.partition }, { it.offset })).map { versionOf(it.value) }
        val text = "A envió $publishedByA, B envió $publishedByB, registros: ${records.size}, distintos: $distinctEventIds\n" +
            "orden de las versiones de X en el log (partición/offset): $watchedCommentVersionOrder"
        Evidence.write("dos-instancias-SKIP-LOCKED.txt", text)
        assertEquals(25, publishedByA); assertEquals(6, publishedByB)
        assertEquals(31, records.size); assertEquals(31, distinctEventIds)
        assertEquals(listOf(2, 1), watchedCommentVersionOrder, "B publicó la versión 2 de X antes de que A publicara la versión 1")
    }

    @Test fun `advisory lock - una sola instancia publicadora a la vez`() {
        PostgresFixture.dataSource.connection.use { firstConnection ->
            PostgresFixture.dataSource.connection.use { secondConnection ->
                fun tryLock(connection: java.sql.Connection) =
                    connection.createStatement().executeQuery("SELECT pg_try_advisory_lock(7001)").use { it.next(); it.getBoolean(1) }
                assertTrue(tryLock(firstConnection))
                assertFalse(tryLock(secondConnection), "la segunda instancia no obtiene el lock mientras la primera lo tiene")
                firstConnection.createStatement().execute("SELECT pg_advisory_unlock(7001)")
                assertTrue(tryLock(secondConnection), "al liberarlo, la segunda sí")
                secondConnection.createStatement().execute("SELECT pg_advisory_unlock(7001)")
            }
        }
    }
}
