package dev.videoeda.videos.infrastructure.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import tools.jackson.databind.json.JsonMapper

private val jsonMapper = JsonMapper.builder().build()

private fun writeProblem(request: HttpServletRequest, response: HttpServletResponse, status: HttpStatus, detail: String) {
    response.status = status.value()
    response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
    response.characterEncoding = Charsets.UTF_8.name()
    val problem = linkedMapOf(
        "title" to status.reasonPhrase,
        "status" to status.value(),
        "detail" to detail,
        "instance" to request.requestURI,
    )
    response.writer.write(jsonMapper.writeValueAsString(problem))
}

val problemAuthenticationEntryPoint = AuthenticationEntryPoint { request, response, authException ->
    val invalidToken = authException is OAuth2AuthenticationException
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, if (invalidToken) "Bearer error=\"invalid_token\"" else "Bearer")
    writeProblem(request, response, HttpStatus.UNAUTHORIZED, "Hace falta un token de acceso válido.")
}

val problemAccessDeniedHandler = AccessDeniedHandler { request, response, _ ->
    writeProblem(request, response, HttpStatus.FORBIDDEN, "No tenés permiso para esta operación.")
}
