package dev.videoeda.videos.infrastructure.web

import com.fasterxml.jackson.annotation.JsonInclude
import dev.videoeda.videos.application.IdempotencyKey
import dev.videoeda.videos.application.VideoNotFoundException
import dev.videoeda.videos.application.VideoQueries
import dev.videoeda.videos.application.VideoService
import dev.videoeda.videos.application.VideoView
import dev.videoeda.videos.domain.VideoId
import dev.videoeda.videos.infrastructure.withLogContext
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

data class UploadVideoRequest(val title: String, val fileSizeBytes: Long, val creatorId: String? = null)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class VideoResponse(val videoId: String, val status: String, val title: String? = null, val creatorId: String? = null)

@RestController
@RequestMapping("/videos")
class VideoController(private val videos: VideoService, private val queries: VideoQueries) {
    private val log = LoggerFactory.getLogger(VideoController::class.java)

    @PostMapping
    fun upload(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody body: UploadVideoRequest,
        @AuthenticationPrincipal token: Jwt,
        request: HttpServletRequest,
    ): ResponseEntity<VideoResponse> {
        val creatorId = token.requireCreatorId()
        if (body.creatorId != null && body.creatorId != creatorId.value) throw ForbiddenException("No podés publicar como otro creator.")
        val result = videos.upload(
            creatorId,
            body.title,
            body.fileSizeBytes,
            IdempotencyKey(idempotencyKey),
            CorrelationIdFilter.of(request),
        )
        withLogContext("aggregateId" to result.videoId.value) { log.info("UploadVideo aceptado: el video queda en {}", result.status) }
        return ResponseEntity.accepted()
            .location(URI.create("/videos/${result.videoId.value}"))
            .body(VideoResponse(result.videoId.value, result.status.name))
    }

    @GetMapping("/{videoId}")
    fun get(@PathVariable videoId: String): VideoResponse {
        val id = VideoId(videoId)
        return queries.findView(id)?.toResponse() ?: throw VideoNotFoundException(id)
    }

    @DeleteMapping("/{videoId}")
    fun delete(@PathVariable videoId: String, @AuthenticationPrincipal token: Jwt, request: HttpServletRequest): ResponseEntity<Void> {
        videos.delete(VideoId(videoId), token.requireCreatorId(), CorrelationIdFilter.of(request))
        withLogContext("aggregateId" to videoId) { log.info("DeleteVideo aceptado") }
        return ResponseEntity.noContent().build()
    }

    private fun VideoView.toResponse() = VideoResponse(videoId.value, status.name, title, creatorId)
}
