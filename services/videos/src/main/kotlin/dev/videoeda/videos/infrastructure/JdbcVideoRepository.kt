package dev.videoeda.videos.infrastructure

import dev.videoeda.videos.application.DuplicateUploadKeyException
import dev.videoeda.videos.application.IdempotencyKey
import dev.videoeda.videos.application.VideoRepository
import dev.videoeda.videos.domain.CreatorId
import dev.videoeda.videos.domain.Video
import dev.videoeda.videos.domain.VideoId
import dev.videoeda.videos.domain.VideoStatus
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet

class JdbcVideoRepository(private val jdbc: JdbcClient) : VideoRepository {
    override fun save(video: Video, uploadKey: IdempotencyKey?) {
        try {
            jdbc.sql(
                """
                INSERT INTO video (video_id, creator_id, title, status, upload_idempotency_key)
                VALUES (:videoId, :creatorId, :title, :status, :uploadKey)
                ON CONFLICT (video_id) DO UPDATE SET status = EXCLUDED.status
                """.trimIndent(),
            )
                .param("videoId", video.id.value)
                .param("creatorId", video.creatorId.value)
                .param("title", video.title)
                .param("status", video.status.name)
                .param("uploadKey", uploadKey?.value)
                .update()
        } catch (duplicate: DuplicateKeyException) {
            if (uploadKey != null && duplicate.message.orEmpty().contains("upload_idempotency_key")) {
                throw DuplicateUploadKeyException(uploadKey)
            }
            throw duplicate
        }
    }

    override fun findByIdForUpdate(videoId: VideoId): Video? =
        jdbc.sql("SELECT video_id, creator_id, title, status FROM video WHERE video_id = :videoId FOR UPDATE")
            .param("videoId", videoId.value)
            .query { rows, _ -> rows.toVideo() }
            .optional()
            .orElse(null)

    override fun findByUploadKey(uploadKey: IdempotencyKey): Video? =
        jdbc.sql("SELECT video_id, creator_id, title, status FROM video WHERE upload_idempotency_key = :uploadKey")
            .param("uploadKey", uploadKey.value)
            .query { rows, _ -> rows.toVideo() }
            .optional()
            .orElse(null)

    private fun ResultSet.toVideo() = Video.reconstitute(
        id = VideoId(getString("video_id")),
        creatorId = CreatorId(getString("creator_id")),
        title = getString("title"),
        status = VideoStatus.valueOf(getString("status")),
    )
}
