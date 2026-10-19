package dev.videoeda.videos.infrastructure.web

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID

object TestTokens {
    const val ISSUER = "http://localhost:8180/realms/video-eda"
    const val AUDIENCE = "video-eda-api"
    const val LUCIA = "usr_01a13161-11be-7119-8e3e-bf07f13eb833"
    const val MATEO = "usr_01a13161-11e3-764d-8aee-4eb50cdb9329"

    val trustedKey: RSAKey = RSAKeyGenerator(2048).keyID("trusted").generate()

    val untrustedKey: RSAKey = RSAKeyGenerator(2048).keyID("untrusted").generate()

    fun token(
        creatorId: String? = LUCIA,
        audience: String = AUDIENCE,
        issuer: String = ISSUER,
        lifetime: Duration = Duration.ofMinutes(5),
        signedWith: RSAKey = trustedKey,
    ): String {
        val issuedAt = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(UUID.randomUUID().toString())
            .audience(audience)
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(issuedAt.plus(lifetime)))
            .apply { creatorId?.let { claim("creator_id", it) } }
            .build()
        val header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signedWith.keyID).type(JOSEObjectType.JWT).build()
        return SignedJWT(header, claims).also { it.sign(RSASSASigner(signedWith)) }.serialize()
    }
}

@TestConfiguration
class TestJwtDecoderConfiguration {
    @Bean
    @Primary
    fun testJwtDecoder(): JwtDecoder = NimbusJwtDecoder.withPublicKey(TestTokens.trustedKey.toRSAPublicKey()).build().also {
        it.setJwtValidator(creatorTokenValidator(TestTokens.ISSUER, TestTokens.AUDIENCE))
    }
}
