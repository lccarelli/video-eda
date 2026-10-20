package dev.videoeda.videos.infrastructure

import java.sql.Connection

private const val PUBLISHER_LOCK_KEY = 7_001L

fun Connection.tryAcquirePublisherLock(): Boolean = prepareStatement("SELECT pg_try_advisory_lock(?)").use { statement ->
    statement.setLong(1, PUBLISHER_LOCK_KEY)
    statement.executeQuery().use { it.next() && it.getBoolean(1) }
}

fun Connection.releasePublisherLock() {
    prepareStatement("SELECT pg_advisory_unlock(?)").use { statement ->
        statement.setLong(1, PUBLISHER_LOCK_KEY)
        statement.executeQuery().use { it.next() }
    }
}
