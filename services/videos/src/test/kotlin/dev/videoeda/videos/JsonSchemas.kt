package dev.videoeda.videos

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import java.nio.file.Files
import java.nio.file.Path

object JsonSchemas {
    private val schemasDirectory = Path.of("../../specs/03-events/schemas").toAbsolutePath().normalize()
    private const val URN_PREFIX = "urn:videoeda:schema:"

    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7) { builder ->
        builder.schemas { iri: String ->
            val schemaFile = schemasDirectory.resolve(iri.removePrefix(URN_PREFIX) + ".json")
            if (iri.startsWith(URN_PREFIX) && Files.exists(schemaFile)) Files.readString(schemaFile) else null
        }
    }

    fun errorsOf(schemaName: String, json: String): List<String> =
        registry.getSchema(SchemaLocation.of(URN_PREFIX + schemaName)).validate(json, InputFormat.JSON).map { it.message }

    fun schemaNameOfType(type: String): String = type.substringAfterLast('.')
}
