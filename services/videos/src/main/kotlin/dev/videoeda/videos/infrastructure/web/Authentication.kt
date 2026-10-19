package dev.videoeda.videos.infrastructure.web

import dev.videoeda.videos.domain.CreatorId
import org.springframework.security.oauth2.jwt.Jwt

const val CREATOR_ID_CLAIM = "creator_id"

class ForbiddenException(detail: String, cause: Throwable? = null) : RuntimeException(detail, cause)

private const val TOKEN_WITHOUT_CREATOR = "El token no identifica a un creator."

fun Jwt.requireCreatorId(): CreatorId {
    val raw = getClaimAsString(CREATOR_ID_CLAIM) ?: throw ForbiddenException(TOKEN_WITHOUT_CREATOR)
    return try {
        CreatorId(raw)
    } catch (malformed: IllegalArgumentException) {
        throw ForbiddenException(TOKEN_WITHOUT_CREATOR, malformed)
    }
}
