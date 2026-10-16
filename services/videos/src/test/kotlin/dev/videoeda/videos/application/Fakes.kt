package dev.videoeda.videos.application

import dev.videoeda.videos.domain.Video
import dev.videoeda.videos.domain.VideoId

class InMemoryVideoRepository : VideoRepository {
    private val videosById = linkedMapOf<VideoId, Video>()
    private val videoIdByUploadKey = mutableMapOf<IdempotencyKey, VideoId>()

    val savedVideos: List<Video> get() = videosById.values.toList()

    override fun save(video: Video, uploadKey: IdempotencyKey?) {
        videosById[video.id] = video
        uploadKey?.let { videoIdByUploadKey[it] = video.id }
    }

    override fun findByIdForUpdate(videoId: VideoId): Video? = videosById[videoId]

    fun savedVideo(videoId: VideoId): Video? = videosById[videoId]

    override fun findByUploadKey(uploadKey: IdempotencyKey): Video? = videoIdByUploadKey[uploadKey]?.let(videosById::get)
}

data class OutboxRecord(val entry: OutboxEntry, val sequence: Int)

class InMemoryOutbox : OutboxWriter {
    val records = mutableListOf<OutboxRecord>()

    override fun appendInCurrentTransaction(entry: OutboxEntry) {
        val sequence = records.count { it.entry.aggregateId == entry.aggregateId && it.entry.topic == entry.topic } + 1
        records += OutboxRecord(entry, sequence)
    }

    fun recordsOf(topic: String) = records.filter { it.entry.topic == topic }
}

class DirectTransaction : Transaction {
    override fun <T> run(block: () -> T): T = block()
}

class InMemoryVideoQueries(private val repository: InMemoryVideoRepository) : VideoQueries {
    override fun findView(videoId: VideoId): VideoView? =
        repository.savedVideo(videoId)?.let { VideoView(it.id, it.creatorId.value, it.title, it.status) }
}

class ScriptedVideoTranscoder(private val script: MutableList<() -> Int>, private val beforeEachCall: () -> Unit = {}) : VideoTranscoder {
    var calls = 0
        private set

    override fun transcode(videoId: VideoId, title: String, storageRef: String, keepAlive: () -> Unit): Int {
        calls++
        beforeEachCall()
        return script.removeFirst()()
    }

    companion object {
        fun returning(durationSeconds: Int) = ScriptedVideoTranscoder(mutableListOf({ durationSeconds }))
    }
}
