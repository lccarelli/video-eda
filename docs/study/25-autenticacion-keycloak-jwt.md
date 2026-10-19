# Autenticación con Keycloak y JWT

> **Estado:** vigente (fase 5 del ADR 0012, ADR 0013) · **Versiones de referencia:** Keycloak 26.8.1, Spring Boot 4.1.1, Spring Security 7.1.1, Nimbus JOSE+JWT 10.9.1 · **Última actualización:** 2026-10-18

## Resumen
La API de Videos es un *resource server*: no guarda credenciales ni emite tokens, solo valida los JWT que emite **Keycloak**. La identidad del creator sale del token (claim `creator_id`), nunca del body. Publicar y borrar exigen token; borrar exige además ser el dueño del video; mirar un video y el health check siguen siendo públicos.

## Por qué importa
Antes cualquiera publicaba a nombre de otra persona (el `creatorId` venía en el body) y borraba videos ajenos. Es la clase de falla de autorización a nivel de objeto (BOLA/IDOR) más común en APIs. Con identidad confiable también se puede hacer una regla de dominio real: "solo el dueño borra".

## Cómo funciona

```mermaid
sequenceDiagram
    participant C as Cliente (curl, app)
    participant K as Keycloak :8180
    participant A as API Videos :8081
    participant D as Dominio (DeleteVideo)
    C->>K: POST /token (usuario, contraseña) [solo dev]
    K-->>C: access_token (iss, aud, creator_id)
    C->>A: DELETE /videos/vid_paella + Bearer
    A->>K: GET JWKS (red de Docker, 1ª vez; luego cache)
    A->>A: firma, vencimiento, iss y aud
    A->>D: DeleteVideoCommand(videoId, requestedBy = creator_id)
    D-->>A: NotVideoOwnerException si no es el dueño
    A-->>C: 204, o 403 problem+json
```

*Por qué este diagrama:* es de **secuencia** porque la pregunta es quién valida qué y en qué orden; la autorización de dueño ocurre en el dominio, después de la autenticación.

- **Validación:** firma RS256 contra el JWKS de Keycloak, vencimiento (con 60 s de tolerancia de reloj, por defecto de Spring), `iss` exactamente `http://localhost:8180/realms/video-eda` y `aud` que contiene `video-eda-api`.
- **`iss` fijo:** Keycloak corre con `KC_HOSTNAME=http://localhost:8180`, así que todos los tokens llevan ese emisor aunque la API los valide dentro de Docker; las claves se leen por `http://keycloak:8080/...` (`jwk-set-uri`).
- **Identidad:** Keycloak copia el atributo de usuario `creatorId` al claim `creator_id` con un *mapper*; el claim tiene el formato `usr_<UUIDv7>` del dominio. El `sub` de Keycloak es un UUID común y no sirve.
- **Contrato compatible:** `creatorId` en el body de `POST /videos` es opcional. Si viene, debe coincidir con el token; si no, `403`.
- **Sin sesión:** no hay cookies, por lo que CSRF está desactivado **a propósito** (el token viaja en un header).
- **Errores:** `401`/`403` salen como `application/problem+json` con textos genéricos; `WWW-Authenticate` solo distingue "sin token" de "token inválido" (RFC 6750).
- **Cabeceras:** `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `Referrer-Policy: no-referrer`.

## Aplicación por componente

| Componente | Cómo aplica | Decisión / regla |
|---|---|---|
| Videos | Resource server; `DeleteVideo` verifica el dueño; `UploadVideo` protege la `Idempotency-Key` | Hecho (SEC-01 a SEC-17) |
| Interactions, Feed, Notifications | Cada API HTTP que se sume valida el mismo emisor y audiencia; los consumers de Kafka no reciben tokens | Reutilizar `creatorTokenValidator` o extraerlo a un módulo compartido |
| Transversal | Keycloak en `compose.yaml`, realm en `deploy/keycloak/`, `smoke.sh` con tokens reales | El flujo usuario y contraseña es solo de desarrollo |

## Ejemplos del dominio
**Caso que falla:** `usr_mateo` pide `DELETE /videos/vid_paella` (de `usr_lucia`) y antes recibía `204`. Con la regla en el dominio recibe `403` ("No sos el dueño de este video."), el video sigue `PUBLISHED` y no se publica `VideoDeleted`. La respuesta no dice quién es el dueño.

**Caso que funciona:** `usr_lucia` pide un token a Keycloak, hace `POST /videos` sin `creatorId`, el video queda a su nombre, el worker lo publica y puede borrarlo; con el humo (`deploy/dev/smoke.sh`) se comprobó con tokens reales de Keycloak.

```gherkin
Escenario: SEC-10 otro creator no puede borrar un video ajeno
  Dado vid_paella PUBLISHED de usr_lucia y un token de usr_mateo
  Cuando hago DELETE /videos/vid_paella
  Entonces la respuesta es 403 problem+json
  Y el video sigue PUBLISHED y no se publica VideoDeleted
```

Los 18 escenarios SEC están en `specs/06-scenarios/12-seguridad.md`; 17 se prueban con tokens firmados por una clave de prueba (`VideosSecurityTest`, `VideoUseCasesTest`) y SEC-18 con Keycloak real.

## Alternativas y trade-offs

| Opción | Ventaja | Costo | Veredicto |
|---|---|---|---|
| **Keycloak real + tests con clave propia** | Valida emisor, audiencia y claim de verdad en el humo; tests rápidos | Keycloak pesa ~1 GB | **Elegida** |
| Testcontainers con Keycloak en cada test | Máximo realismo | Lento y pesado | Descartada |
| mock-oauth2-server | Liviano | Laura prefirió el emisor real | Descartada |
| Quitar `creatorId` del body | Más simple | Rompe a los clientes | Descartada (decisión de Laura) |
| Validar `creator_id` en un filtro | Un solo lugar | Mezcla autorización de negocio con la infraestructura | Descartada: regla de dominio |

## Reglas para el spec
1. La identidad la define el token; el body nunca.
2. Autenticación en la infraestructura web; autorización de dueño en la capa de aplicación.
3. Todo lo que no está declarado se deniega.
4. Los errores de seguridad son genéricos y no revelan datos de otros creators.
5. Los tests de integración no levantan Keycloak; el humo sí.

## Fuentes
- Keycloak v26.8.1 (API de GitHub `releases/latest`, 2026-10-13) y existencia de la imagen `quay.io/keycloak/keycloak:26.8.1`. Consultado 2026-10-18.
- Keycloak 26.8.1, documentación del user profile (`docs/documentation/server_admin/topics/users/user-profile.adoc`): atributos gestionados y no gestionados. Consultado 2026-10-18.
- Spring Boot 4.1.1: starters `spring-boot-starter-security-oauth2-resource-server` y `spring-boot-starter-security-test` (Maven Central); `spring-security.version` 7.1.1 en `spring-boot-dependencies`. Consultado 2026-10-18.
- Ejecución propia: token real de Keycloak decodificado (claims `iss`, `aud`, `creator_id`, `azp`, `typ`; algoritmo RS256; vigencia 300 s); `docker compose up` con Keycloak healthy y el humo con tokens reales; escaneo de la imagen de Keycloak con Trivy (0 HIGH/CRITICAL con arreglo).

## No verificado
- **Discrepancia documentación/servidor:** la documentación del user profile muestra `"unmanagedAttributePolicy": "DISABLED"` en su ejemplo, pero Keycloak 26.8.1 lo rechaza en la importación (acepta `ENABLED`, `ADMIN_EDIT`, `ADMIN_VIEW`; para "deshabilitado" hay que omitir la clave). Se tomó el comportamiento observado.
- **Rotación de claves:** no probé que la API siga validando tras una rotación de claves de Keycloak ni cuánto cachea el JWKS.
- **Revocación:** los tokens valen hasta que vencen (5 minutos); no hay introspección ni lista de revocados.
- **TLS:** todo va en HTTP local (`start-dev`, sin TLS); en producción haría falta HTTPS y `sslRequired` estricto.
- **Flujo usuario y contraseña (*direct access grants*):** está desaconsejado por OAuth 2.1; se usa solo para desarrollo. No probé el flujo de código con PKCE.
- **Protección contra fuerza bruta de Keycloak:** está activada en el realm (`bruteForceProtected`), pero no la ejercité.
- **Límite de tasa (rate limiting):** no hay; un cliente con token válido puede publicar sin límite.
- **CSRF desactivado:** es una decisión razonada para una API sin cookies; SonarQube no lo marcó como hotspot, y no lo contrasté con otra herramienta.
- **El claim `creator_id` en un token de otro cliente:** solo el cliente `video-eda-cli` tiene el mapper; no hay otro cliente que probar.
- **Varias instancias de Videos:** no probé que dos instancias compartan la validación (no hay estado, pero tampoco lo medí).
- **Tolerancia de reloj:** el margen de 60 s es el valor por defecto de Spring; no lo ajusté.

## Historial

| Fecha | Cambio |
|---|---|
| 2026-10-18 | Creación: Keycloak 26.8.1, resource server en Videos, regla de dueño en `DeleteVideo`, protección de `Idempotency-Key`, `smoke.sh` con tokens reales |
