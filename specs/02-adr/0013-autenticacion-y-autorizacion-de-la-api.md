# ADR 0013: Autenticación y autorización de la API

> **Estado:** **Aceptado** (Laura, 2026-10-17: eligió Keycloak desde ya en el ADR 0012, aprobó el plan de la fase 5 y pidió mantener el contrato) · **Última actualización:** 2026-10-18 · Desarrolla la fase 5 del ADR 0012.

## Contexto
Hasta ahora `POST /videos` aceptaba el `creatorId` en el body (cualquiera publicaba a nombre de otra persona) y `DELETE /videos/{id}` no verificaba quién lo pedía. Para un repositorio público con prácticas de seguridad desde el inicio (ADR 0012) la identidad tiene que venir de un emisor confiable, no del cliente.

## Decisión
1. **Emisor:** Keycloak (OIDC) dentro de `compose.yaml`, realm `video-eda`. La API es un *resource server*: valida JWT, no emite ni guarda credenciales.
2. **Validación del token en Videos:** firma contra el JWKS del emisor, `iss` fijo (`http://localhost:8180/realms/video-eda`, el mismo para quien pide el token desde el host y para la API dentro de Docker) y `aud` que contenga `video-eda-api`. Expirado, mal firmado o de otra audiencia: `401`.
3. **Identidad del creator:** claim propio `creator_id` con el formato `usr_<UUIDv7>` (nota 17), que Keycloak agrega desde un atributo del usuario. No se usa `sub`: es un UUID común y el dominio exige el prefijo y la versión 7. Un token válido **sin** ese claim no es de un creator: `403`.
4. **Qué exige autenticación:**

| Endpoint | Acceso |
|---|---|
| `POST /videos` | Token válido con `creator_id` |
| `DELETE /videos/{id}` | Token válido con `creator_id` **y ser el dueño** del video |
| `GET /videos/{id}` | Público (mirar es público) |
| `GET /actuator/health` | Público |
| Todo lo demás | `401` por defecto (denegar lo no declarado) |

5. **Compatibilidad del contrato (decisión de Laura):** el campo `creatorId` del body de `POST /videos` pasa a **opcional**, no se elimina. Ausente: se usa el del token. Presente e igual al del token: se acepta (los clientes viejos siguen funcionando). Presente y **distinto**: `403`, sin crear nada. La identidad siempre es la del token; el body nunca la define.
6. **Autorización del dueño:** es una regla de dominio y vive en el caso de uso `DeleteVideo` (`NotVideoOwnerException`), no en un filtro; el agregado y la capa de aplicación no conocen Spring Security. Solo la infraestructura web lee el token.
7. **Idempotency-Key:** una clave ya usada por **otro** creator responde `409` sin revelar el `videoId` ajeno.
8. **Errores:** `401` y `403` salen como `application/problem+json` (RFC 9457), como los demás errores, sin detalles internos. Cabeceras de seguridad por defecto de Spring Security (`X-Content-Type-Options`, `Cache-Control: no-store`, `X-Frame-Options`) más `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`.
9. **Desarrollo y pruebas:** el realm trae dos creators de ejemplo (`lucia`, `mateo`) y un cliente de desarrollo con *direct access grants* (usuario y contraseña) para pedir tokens con `curl`; es un flujo desaconsejado en producción y queda marcado como solo para desarrollo. Los tests de integración firman tokens con una clave propia y se validan con un `JwtDecoder` de prueba; el Keycloak **real** se ejercita en `deploy/dev/smoke.sh` y en el humo de CI.

## Alternativas
| Opción | Por qué no |
|---|---|
| Quitar `creatorId` del body | Rompe a los clientes existentes sin necesidad; basta con que el token mande |
| Ignorar un `creatorId` distinto sin avisar | El cliente creería que publicó como otro; el fallo silencioso es peor que un `403` |
| Usar `sub` como creator id | UUID sin prefijo ni versión 7; obligaría a una tabla de mapeo |
| mock-oauth2-server | Más liviano, pero Laura prefirió el emisor real (ADR 0012) |
| API key por creator | No es estándar, no expira ni se revoca de forma centralizada |

## Consecuencias
- `compose.yaml` suma Keycloak (~1 GB de RAM) al arranque por defecto.
- `VideosApplicationTest` y los demás tests de la API pasan a enviar tokens.
- Los tokens de Keycloak en dev se emiten para `localhost:8180`; si el puerto cambia, hay que cambiar el `iss` esperado.

## Sin verificar (experimentos pendientes)
- Que Keycloak 26.8.1 en `start-dev` con importación de realm arranque en esta máquina y en CI.
- Que el `iss` fijo funcione con tokens pedidos desde el host y validados dentro de Docker.
- Que el mapper de atributo a claim y el de audiencia produzcan los claims esperados.
- Rotación de claves del JWKS (la API cachea el JWKS; no se probó una rotación).

## Respaldo
ADR 0012 (fase 5), ADR 0009 (el dominio no conoce frameworks), nota 17 (ids), `specs/06-scenarios/12-seguridad.md`, nota 25 (se agrega al implementar).
