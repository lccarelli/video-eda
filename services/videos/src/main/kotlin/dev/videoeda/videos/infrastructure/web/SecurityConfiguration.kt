package dev.videoeda.videos.infrastructure.web

import jakarta.servlet.DispatcherType
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter

private const val CONTENT_SECURITY_POLICY = "default-src 'none'; frame-ancestors 'none'"

@Configuration
@EnableWebSecurity
class SecurityConfiguration {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { requests ->
                requests
                    .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    .requestMatchers(HttpMethod.GET, "/videos/*").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/videos").authenticated()
                    .requestMatchers(HttpMethod.DELETE, "/videos/*").authenticated()
                    .anyRequest().denyAll()
            }
            .oauth2ResourceServer { resourceServer ->
                resourceServer.jwt(Customizer.withDefaults())
                resourceServer.authenticationEntryPoint(problemAuthenticationEntryPoint)
                resourceServer.accessDeniedHandler(problemAccessDeniedHandler)
            }
            .exceptionHandling {
                it.authenticationEntryPoint(problemAuthenticationEntryPoint).accessDeniedHandler(problemAccessDeniedHandler)
            }
            .headers { headers ->
                headers.contentSecurityPolicy { it.policyDirectives(CONTENT_SECURITY_POLICY) }
                headers.referrerPolicy { it.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER) }
            }
        return http.build()
    }

    @Bean
    fun jwtDecoder(
        @Value("\${videos.security.jwk-set-uri}") jwkSetUri: String,
        @Value("\${videos.security.issuer}") issuer: String,
        @Value("\${videos.security.audience}") audience: String,
    ): JwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build().also {
        it.setJwtValidator(creatorTokenValidator(issuer, audience))
    }
}

fun creatorTokenValidator(issuer: String, audience: String): OAuth2TokenValidator<Jwt> =
    DelegatingOAuth2TokenValidator(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator(audience))

private fun audienceValidator(audience: String) = OAuth2TokenValidator<Jwt> { token ->
    if (audience in token.audience.orEmpty()) {
        OAuth2TokenValidatorResult.success()
    } else {
        OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token", "la audiencia no incluye $audience", null))
    }
}
