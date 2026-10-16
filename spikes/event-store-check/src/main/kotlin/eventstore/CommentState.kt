package eventstore

class VersionConflict(val streamId: String, val expectedVersion: Int, cause: Throwable) :
    RuntimeException("conflicto de versión en $streamId (se esperaba $expectedVersion): ${cause.message}", cause)

class CommentRejected(message: String) : RuntimeException(message)

data class CommentState(
    val commentId: String,
    val videoId: String,
    val authorId: String,
    val text: String,
    val deleted: Boolean,
    val version: Int,
)
