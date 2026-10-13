package spike

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import videoeda.videos.VideoPublishedProducer
import java.nio.file.Files
import java.nio.file.Path

class SchemaContractTest {
    private val schemasDir = Path.of("../../specs/03-events/schemas").toAbsolutePath().normalize()

    private val urnPrefix = "urn:videoeda:schema:"
    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7) { builder ->
        builder.schemas { iri: String ->
            val schemaFile = schemasDir.resolve(iri.removePrefix(urnPrefix) + ".json")
            if (iri.startsWith(urnPrefix) && Files.exists(schemaFile)) Files.readString(schemaFile) else null
        }
    }
    private val schema = registry.getSchema(SchemaLocation.of(urnPrefix + "video-published"))

    private fun errors(json: String) = schema.validate(json, InputFormat.JSON).map { it.message }

    @Test
    fun `cero - un objeto vacio no valida y lista los campos que faltan`() {
        val e = errors("{}")
        println("CERO errores=$e")
        assertFalse(e.isEmpty())
    }

    @Test
    fun `uno - el ejemplo de AsyncAPI valida`() {
        val example = """{"specversion":"1.0","id":"evt_01a13161-12e6-7e09-b67f-10ca57c43328","source":"/videos",
          "type":"dev.videoeda.videos.video-published","subject":"vid_01a13161-1277-7e7b-9435-b109a7e95989",
          "time":"2026-10-12T19:30:00Z","datacontenttype":"application/json",
          "correlationid":"cor_01a13161-13c4-7752-b78c-4c2b8c8039fb","sequence":1,
          "data":{"videoId":"vid_01a13161-1277-7e7b-9435-b109a7e95989","creatorId":"usr_01a13161-11be-7119-8e3e-bf07f13eb833",
          "title":"Mi primer video","durationSeconds":184,"publishedAt":"2026-10-12T19:29:58Z"}}"""
        assertEquals(emptyList<String>(), errors(example))
    }

    @Test
    fun `muchos - el producer real valida y cada ruptura de CTR-01 falla`() {
        assertEquals(emptyList<String>(), errors(VideoPublishedProducer(breakMode = null).videoPublished().value))
        val missing = errors(VideoPublishedProducer(breakMode = "creatorId-missing").videoPublished().value)
        val wrongType = errors(VideoPublishedProducer(breakMode = "creatorId-type").videoPublished().value)
        println("CTR-01 creatorId-missing errores=$missing")
        println("CTR-01 creatorId-type errores=$wrongType")
        assertTrue(missing.isNotEmpty())
        assertTrue(wrongType.isNotEmpty())
    }

    @Test
    fun `CTR-02 un campo opcional nuevo no rompe el schema`() {
        assertEquals(emptyList<String>(), errors(VideoPublishedProducer(breakMode = "extra-optional-field").videoPublished().value))
    }

    @Test
    fun `un id con UUID version 4 se rechaza`() {
        val v4 = VideoPublishedProducer().videoPublished().value
            .replace("vid_01a13161-1277-7e7b-9435-b109a7e95989", "vid_01a13161-1277-4e7b-9435-b109a7e95989")
        assertFalse(errors(v4).isEmpty())
    }

    @Test
    fun `un publishedAt que no es date-time se rechaza (format se valida)`() {
        val bad = VideoPublishedProducer().videoPublished().value.replace("2026-10-12T19:29:58Z", "ayer a la tarde")
        val e = errors(bad)
        println("FORMAT date-time errores=$e")
        assertFalse(e.isEmpty())
    }
}
