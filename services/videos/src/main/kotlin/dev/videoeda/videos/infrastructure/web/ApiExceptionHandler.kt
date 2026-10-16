package dev.videoeda.videos.infrastructure.web

import dev.videoeda.videos.application.IdempotencyKeyInUseException
import dev.videoeda.videos.application.NotVideoOwnerException
import dev.videoeda.videos.application.VideoNotFoundException
import dev.videoeda.videos.domain.EmptyVideoFileException
import dev.videoeda.videos.domain.InvalidVideoTransitionException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(EmptyVideoFileException::class)
    fun emptyFile(exception: EmptyVideoFileException) = problem(HttpStatus.UNPROCESSABLE_CONTENT, exception.message)

    @ExceptionHandler(InvalidVideoTransitionException::class)
    fun invalidTransition(exception: InvalidVideoTransitionException) = problem(HttpStatus.CONFLICT, exception.message)

    @ExceptionHandler(VideoNotFoundException::class)
    fun notFound(exception: VideoNotFoundException) = problem(HttpStatus.NOT_FOUND, exception.message)

    @ExceptionHandler(NotVideoOwnerException::class)
    fun notOwner() = problem(HttpStatus.FORBIDDEN, "No sos el dueño de este video.")

    @ExceptionHandler(ForbiddenException::class)
    fun forbidden(exception: ForbiddenException) = problem(HttpStatus.FORBIDDEN, exception.message)

    @ExceptionHandler(IdempotencyKeyInUseException::class)
    fun idempotencyKeyInUse() = problem(HttpStatus.CONFLICT, "La Idempotency-Key ya está en uso.")

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(exception: IllegalArgumentException) = problem(HttpStatus.BAD_REQUEST, exception.message)

    private fun problem(status: HttpStatus, detail: String?) = ProblemDetail.forStatusAndDetail(status, detail ?: status.reasonPhrase)
}
