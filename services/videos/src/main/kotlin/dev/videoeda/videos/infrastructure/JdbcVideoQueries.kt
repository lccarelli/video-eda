package dev.videoeda.videos.infrastructure

import dev.videoeda.videos.application.VideoQueries
import dev.videoeda.videos.application.VideoView
import dev.videoeda.videos.domain.VideoId
import dev.videoeda.videos.domain.VideoStatus
import org.springframework.jdbc.core.simple.JdbcClient

class JdbcVideoQueries(private val jdbc: JdbcClient) : VideoQueries {
    override fun findView(videoId: VideoId): VideoView? =
        jdbc.sql("SELECT video_id, creator_id, title, status FROM video WHERE video_id = :videoId")
            .param("videoId", videoId.value)
            .query { rows, _ ->
                VideoView(
                    videoId = VideoId(rows.getString("video_id")),
                    creatorId = rows.getString("creator_id"),
                    title = rows.getString("title"),
                    status = VideoStatus.valueOf(rows.getString("status")),
                )
            }
            .optional()
            .orElse(null)
}
