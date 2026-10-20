package dev.videoeda.videos.infrastructure

import dev.videoeda.videos.application.VideoTranscoder
import dev.videoeda.videos.domain.VideoId
import java.util.concurrent.atomic.AtomicInteger

class CountingTranscoder(val calls: AtomicInteger, private val behaviour: (callNumber: Int) -> Int) : VideoTranscoder {
    override fun transcode(videoId: VideoId, title: String, storageRef: String, keepAlive: () -> Unit) = behaviour(calls.incrementAndGet())
}

class SlowTranscoder(val calls: AtomicInteger, private val seconds: Int, private val renewsLock: Boolean) : VideoTranscoder {
    override fun transcode(videoId: VideoId, title: String, storageRef: String, keepAlive: () -> Unit): Int {
        calls.incrementAndGet()
        repeat(seconds) {
            Thread.sleep(1_000)
            if (renewsLock) keepAlive()
        }
        return 184
    }
}
