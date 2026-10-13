package eventstore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.SQLException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Evidence {
    private val dir = File("build/evidence").also { it.mkdirs() }
    fun write(name: String, text: String) {
        File(dir, name).writeText(text)
        println("[EVIDENCE $name]\n$text")
    }
}

class EventStoreTest {
    private val store = CommentEventStore(PostgresFixture.dataSource)
    private val videoId = "vid_01a13161-1208-73ad-b8df-5e1e8e69b948"
    private val authorId = "usr_01a13161-112a-7681-b280-02ac0205cd15"

    @BeforeEach fun clean() = PostgresFixture.resetTables()

    private fun newComment(text: String = "Muy bueno"): String {
        val commentId = PrefixedIds.generate("cmt")
        store.postComment(commentId, videoId, authorId, text)
        return commentId
    }

    @Test fun `ES-01 stream sin eventos no existe y EditComment se rechaza sin escribir`() {
        val commentId = PrefixedIds.generate("cmt")
        assertNull(store.rehydrateComment(commentId))
        assertThrows(CommentRejected::class.java) { store.editComment(commentId, "hola") }
        assertEquals(0, PostgresFixture.countRows("comment_events"))
        assertEquals(0, PostgresFixture.countRows("comment_view"))
    }

    @Test fun `ES-02 el primer append espera stream inexistente y guarda version 1`() {
        val commentId = PrefixedIds.generate("cmt")
        val e = store.postComment(commentId, videoId, authorId, "Muy bueno")
        assertEquals(1, e.version)

        val thrown = assertThrows(VersionConflict::class.java) {
            store.appendEvent(commentId, 0, COMMENT_POSTED_TYPE, mapOf("commentId" to commentId, "videoId" to videoId, "authorId" to authorId, "text" to "otra", "postedAt" to "2026-10-12T18:30:07Z"))
        }
        Evidence.write("ES-02-conflicto-primer-append.txt", thrown.message.orEmpty())
        assertEquals(1, PostgresFixture.countRows("comment_events"))
    }

    @Test fun `ES-03 publicar guarda un evento y actualiza la vista`() {
        val commentId = newComment()
        val events = store.readStreamEvents(commentId)
        assertEquals(1, events.size)
        assertEquals(COMMENT_POSTED_TYPE, events[0].type)
        assertEquals(1, events[0].version)
        val view = PostgresFixture.query("SELECT status, version, text FROM comment_view WHERE comment_id = '$commentId'") {
            Triple(it.getString(1), it.getInt(2), it.getString(3))
        }.single()
        assertEquals(Triple("ACTIVE", 1, "Muy bueno"), view)
    }

    @Test fun `ES-03 d - la proyeccion se revierte si falla antes del commit (misma transaccion)`() {
        val commentId = PrefixedIds.generate("cmt")
        assertThrows(IllegalStateException::class.java) {
            store.appendEvent(
                commentId, 0, COMMENT_POSTED_TYPE,
                mapOf("commentId" to commentId, "videoId" to videoId, "authorId" to authorId, "text" to "x", "postedAt" to "2026-10-12T18:30:07Z"),
                beforeCommit = { error("falla simulada justo antes del COMMIT") },
            )
        }
        assertEquals(0, PostgresFixture.countRows("comment_events"), "el evento no debe quedar")
        assertEquals(0, PostgresFixture.countRows("comment_view"), "la vista no debe quedar")
    }

    @Test fun `ES-04 rehidratar un stream de un evento`() {
        val commentId = newComment("Muy bueno")
        val state = store.rehydrateComment(commentId)!!
        assertEquals(CommentState(commentId, videoId, authorId, "Muy bueno", false, 1), state)
    }

    @Test fun `ES-05 la historia completa se conserva`() {
        val commentId = newComment("v1")
        store.editComment(commentId, "v2")
        store.editComment(commentId, "v3")
        store.deleteComment(commentId)
        val events = store.readStreamEvents(commentId)
        assertEquals(listOf(1, 2, 3, 4), events.map { it.version })
        assertEquals(listOf("v1", "v2", "v3"), events.filter { it.type != COMMENT_DELETED_TYPE }.map { it.data["text"].asText() })
        val state = store.rehydrateComment(commentId)!!
        assertTrue(state.deleted)
        assertEquals(4, state.version)
    }

    @Test fun `ES-06 dos ediciones concurrentes - una gana y la otra recibe conflicto (20 repeticiones)`() {
        val executor = Executors.newFixedThreadPool(2)
        var firstMessage = ""
        repeat(20) { iteration ->
            val commentId = newComment("original $iteration")
            val startBarrier = CyclicBarrier(2)
            val tasks = listOf("A", "B").map { editor ->
                executor.submit<Result<StoredEvent>> {
                    val state = store.rehydrateComment(commentId)!!
                    startBarrier.await(5, TimeUnit.SECONDS)
                    runCatching {
                        store.appendEvent(
                            commentId, state.version, COMMENT_EDITED_TYPE,
                            mapOf("commentId" to commentId, "videoId" to videoId, "text" to "editado por $editor", "editedAt" to "2026-10-12T18:31:00Z"),
                        )
                    }
                }
            }
            val attemptResults = tasks.map { it.get(10, TimeUnit.SECONDS) }
            val winners = attemptResults.count { it.isSuccess }
            val conflicts = attemptResults.count { it.exceptionOrNull() is VersionConflict }
            assertEquals(1, winners, "iteración $iteration: debe haber un único ganador")
            assertEquals(1, conflicts, "iteración $iteration: el otro debe recibir VersionConflict, no otro error: ${attemptResults.map { it.exceptionOrNull() }}")
            if (iteration == 0) firstMessage = attemptResults.first { it.isFailure }.exceptionOrNull()!!.cause!!.message.orEmpty()

            store.editComment(commentId, "reintento del perdedor")
            assertEquals(listOf(1, 2, 3), store.readStreamEvents(commentId).map { it.version })
            assertEquals(3, PostgresFixture.query("SELECT version FROM comment_view WHERE comment_id = '$commentId'") { it.getInt(1) }.single())
        }
        executor.shutdown()
        Evidence.write("ES-06-error-literal-del-perdedor.txt", firstMessage)
    }

    @Test fun `ES-06 b - ocho escritores sobre la misma version, un solo ganador`() {
        val commentId = newComment()
        val writerCount = 8
        val executor = Executors.newFixedThreadPool(writerCount)
        val startBarrier = CyclicBarrier(writerCount)
        val attemptResults = (1..writerCount).map { writer ->
            executor.submit<Result<StoredEvent>> {
                startBarrier.await(5, TimeUnit.SECONDS)
                runCatching {
                    store.appendEvent(commentId, 1, COMMENT_EDITED_TYPE, mapOf("commentId" to commentId, "videoId" to videoId, "text" to "w$writer", "editedAt" to "2026-10-12T18:31:00Z"))
                }
            }
        }.map { it.get(10, TimeUnit.SECONDS) }
        executor.shutdown()
        assertEquals(1, attemptResults.count { it.isSuccess })
        assertEquals(writerCount - 1, attemptResults.count { it.exceptionOrNull() is VersionConflict })
        assertEquals(listOf(1, 2), store.readStreamEvents(commentId).map { it.version })
    }

    @Test fun `ES-07 reproyeccion - vaciar y reconstruir la vista la deja identica y no toca published_at`() {
        val commentIds = (1..30).map { newComment("c$it") }
        commentIds.forEachIndexed { index, commentId ->
            if (index % 2 == 0) store.editComment(commentId, "editado $index")
            if (index % 5 == 0) store.deleteComment(commentId)
            if (index % 3 == 0) store.editComment(commentIds[(index + 1) % commentIds.size], "cruzado $index")
        }

        PostgresFixture.dataSource.connection.use { it.createStatement().execute("UPDATE comment_events SET published_at = now() WHERE global_seq % 2 = 0") }
        fun snapshot() = PostgresFixture.query("SELECT comment_id, text, status, version FROM comment_view ORDER BY comment_id") {
            listOf(it.getString(1), it.getString(2), it.getString(3), it.getInt(4))
        }
        fun published() = PostgresFixture.query("SELECT global_seq, published_at FROM comment_events ORDER BY global_seq") { it.getLong(1) to it.getTimestamp(2) }
        val before = snapshot()
        val pubBefore = published()
        assertEquals(30, before.size)
        store.rebuildCommentView()
        assertEquals(before, snapshot())
        assertEquals(pubBefore, published())
    }

    @Test fun `ES-09 editar un comentario borrado se rechaza sin escribir`() {
        val commentId = newComment()
        store.deleteComment(commentId)
        val eventCountBefore = PostgresFixture.countRows("comment_events")
        assertThrows(CommentRejected::class.java) { store.editComment(commentId, "tarde") }
        assertEquals(eventCountBefore, PostgresFixture.countRows("comment_events"))
    }

    @Test fun `ES-10 insertar a mano una version repetida falla por UNIQUE (stream_id, version)`() {
        val commentId = newComment()
        store.editComment(commentId, "v2")
        val thrown = assertThrows(SQLException::class.java) {
            rawInsert(PrefixedIds.generate("evt"), commentId, 2)
        }
        assertEquals("23505", thrown.sqlState)
        assertTrue(thrown.message.orEmpty().contains("stream_id_version"), thrown.message)
        Evidence.write("ES-10-error-literal.txt", "sqlState=${thrown.sqlState}\n${thrown.message}")
    }

    @Test fun `ID - el CHECK acepta UUIDv7 y rechaza prefijo equivocado y UUID v4`() {
        rawInsert(PrefixedIds.generate("evt"), PrefixedIds.generate("cmt"), 1)
        val rejected = mutableListOf<String>()
        fun expect23514(label: String, f: () -> Unit) {
            val thrown = assertThrows(SQLException::class.java, f)
            assertEquals("23514", thrown.sqlState, "$label: $thrown")
            rejected += "$label -> sqlState=${thrown.sqlState} ${thrown.message!!.lines().first()}"
        }
        expect23514("stream_id con UUID v4") { rawInsert(PrefixedIds.generate("evt"), PrefixedIds.generateWithUuidV4("cmt"), 1) }
        expect23514("event_id con UUID v4") { rawInsert(PrefixedIds.generateWithUuidV4("evt"), PrefixedIds.generate("cmt"), 1) }
        expect23514("stream_id con prefijo vid_") { rawInsert(PrefixedIds.generate("evt"), PrefixedIds.generate("vid"), 1) }
        expect23514("event_id con prefijo cmt_") { rawInsert(PrefixedIds.generate("cmt"), PrefixedIds.generate("cmt"), 1) }
        expect23514("stream_id sin prefijo") { rawInsert(PrefixedIds.generate("evt"), java.util.UUID.randomUUID().toString(), 1) }
        expect23514("stream_id corto c-1") { rawInsert(PrefixedIds.generate("evt"), "c-1", 1) }
        assertFalse(rejected.isEmpty())
        Evidence.write("ID-check-rechazos.txt", rejected.joinToString("\n"))
    }

    private fun rawInsert(eventId: String, streamId: String, version: Int) =
        PostgresFixture.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO comment_events (event_id, stream_id, version, type, data, metadata) VALUES (?, ?, ?, ?, '{}'::jsonb, '{}'::jsonb)",
            ).use { statement ->
                statement.setString(1, eventId)
                statement.setString(2, streamId)
                statement.setInt(3, version)
                statement.setString(4, COMMENT_POSTED_TYPE)
                statement.executeUpdate()
            }
        }

    @Test fun `ID-03 monotonia de Uuid generateV7 dentro del mismo milisegundo`() {
        val generatedCount = 200_000
        val generatedIds = List(generatedCount) { kotlin.uuid.Uuid.generateV7().toString() }
        val uuidV7Pattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        assertTrue(generatedIds.all { uuidV7Pattern.matches(it) }, "todos deben ser UUIDv7 válidos")
        fun timestampPartOf(uuid: String) = uuid.replace("-", "").take(12)
        var consecutiveInSameMillisecond = 0
        var notStrictlyIncreasing = 0
        var firstViolation = ""
        for (index in 1 until generatedCount) {
            val previous = generatedIds[index - 1]
            val current = generatedIds[index]
            if (timestampPartOf(current) == timestampPartOf(previous)) {
                consecutiveInSameMillisecond++
                if (current <= previous) {
                    notStrictlyIncreasing++
                    if (firstViolation.isEmpty()) firstViolation = "$previous luego $current"
                }
            }
        }
        val timestampDecreases = (1 until generatedCount)
            .count { timestampPartOf(generatedIds[it]) < timestampPartOf(generatedIds[it - 1]) }
        val distinctCount = generatedIds.toSet().size
        val report = """
            ids generados: $generatedCount (en un bucle cerrado, un solo hilo)
            ids distintos: $distinctCount
            pares consecutivos dentro del mismo milisegundo: $consecutiveInSameMillisecond
            ...de ellos, NO estrictamente crecientes (orden lexicográfico): $notStrictlyIncreasing
            retrocesos de timestamp entre consecutivos: $timestampDecreases
            primer par fuera de orden: ${firstViolation.ifEmpty { "(ninguno)" }}
        """.trimIndent()
        Evidence.write("ID-03-monotonia.txt", report)
        assertEquals(generatedCount, distinctCount, "no debe haber ids repetidos")
        assertNotNull(report)
    }
}
