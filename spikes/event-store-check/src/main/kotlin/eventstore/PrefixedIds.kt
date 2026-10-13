package eventstore

import kotlin.uuid.Uuid

object PrefixedIds {
    fun generate(prefix: String): String = "${prefix}_${Uuid.generateV7()}"

    fun generateWithUuidV4(prefix: String): String = "${prefix}_${Uuid.random()}"
}
