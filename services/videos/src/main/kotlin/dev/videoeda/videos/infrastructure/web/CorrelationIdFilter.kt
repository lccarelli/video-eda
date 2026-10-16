package dev.videoeda.videos.infrastructure.web

import dev.videoeda.videos.domain.CorrelationId
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val correlationId = request.getHeader(HEADER)?.let { runCatching { CorrelationId(it) }.getOrNull() } ?: CorrelationId.generate()
        request.setAttribute(ATTRIBUTE, correlationId)
        response.setHeader(HEADER, correlationId.value)
        MDC.putCloseable("correlationId", correlationId.value).use { chain.doFilter(request, response) }
    }

    companion object {
        const val HEADER = "X-Correlation-Id"
        const val ATTRIBUTE = "correlationId"

        fun of(request: HttpServletRequest): CorrelationId = request.getAttribute(ATTRIBUTE) as CorrelationId
    }
}
