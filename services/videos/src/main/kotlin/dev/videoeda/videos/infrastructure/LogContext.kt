package dev.videoeda.videos.infrastructure

import org.slf4j.MDC

internal fun <T> withLogContext(vararg fields: Pair<String, String>, block: () -> T): T {
    fields.forEach { (name, value) -> MDC.put(name, value) }
    try {
        return block()
    } finally {
        fields.forEach { (name, _) -> MDC.remove(name) }
    }
}
