# Seguridad de la API

> **Estado:** vigente (fase 5 del ADR 0012, ADR 0013) · **Última actualización:** 2026-10-18

Escenarios de autenticación y autorización de `Videos`. Alias de `specs/01-domain/ids-de-ejemplo.md`: `usr_lucia` y `usr_mateo` son creators; `vid_paella` es de Lucía.

```gherkin
@seguridad @integracion
Escenario: SEC-01 publicar sin token se rechaza
  Cuando hago POST /videos sin Authorization
  Entonces la respuesta es 401 application/problem+json
  Y no se crea ningún video ni se escribe nada en el outbox

@seguridad @integracion
Escenario: SEC-02 el creator sale del token
  Dado un token de usr_lucia
  Cuando hago POST /videos con título y tamaño, sin creatorId
  Entonces la respuesta es 202
  Y el video queda con creatorId usr_lucia

@seguridad @integracion
Escenario: SEC-03 un creatorId igual al del token se acepta (compatibilidad)
  Dado un token de usr_lucia
  Cuando hago POST /videos con creatorId usr_lucia en el body
  Entonces la respuesta es 202

@seguridad @integracion
Escenario: SEC-04 un creatorId distinto al del token se rechaza
  Dado un token de usr_lucia
  Cuando hago POST /videos con creatorId usr_mateo en el body
  Entonces la respuesta es 403 application/problem+json
  Y no se crea ningún video

@seguridad @integracion
Escenario: SEC-05 un token vencido se rechaza
  Dado un token de usr_lucia ya expirado
  Cuando hago POST /videos
  Entonces la respuesta es 401

@seguridad @integracion
Escenario: SEC-06 un token firmado con otra clave se rechaza
  Dado un token con la forma correcta pero firmado por una clave que el emisor no publica
  Cuando hago POST /videos
  Entonces la respuesta es 401

@seguridad @integracion
Escenario: SEC-07 un token de otra audiencia se rechaza
  Dado un token válido cuyo aud no incluye video-eda-api
  Cuando hago POST /videos
  Entonces la respuesta es 401

@seguridad @integracion
Escenario: SEC-08 un token sin creator_id no es de un creator
  Dado un token válido, de la audiencia correcta, sin el claim creator_id
  Cuando hago POST /videos
  Entonces la respuesta es 403

@seguridad @unit @integracion
Escenario: SEC-09 el dueño borra su video
  Dado vid_paella PUBLISHED de usr_lucia y un token de usr_lucia
  Cuando hago DELETE /videos/vid_paella
  Entonces la respuesta es 204
  Y se publica VideoDeleted

@seguridad @unit @integracion
Escenario: SEC-10 otro creator no puede borrar un video ajeno
  Dado vid_paella PUBLISHED de usr_lucia y un token de usr_mateo
  Cuando hago DELETE /videos/vid_paella
  Entonces la respuesta es 403
  Y el video sigue PUBLISHED y no se publica VideoDeleted

@seguridad @integracion
Escenario: SEC-11 borrar sin token se rechaza
  Cuando hago DELETE /videos/vid_paella sin Authorization
  Entonces la respuesta es 401

@seguridad @integracion
Escenario: SEC-12 mirar un video es público
  Cuando hago GET /videos/vid_paella sin Authorization
  Entonces la respuesta es 200

@seguridad @integracion
Escenario: SEC-13 el health check es público
  Cuando hago GET /actuator/health sin Authorization
  Entonces la respuesta es 200

@seguridad @integracion
Escenario: SEC-14 lo no declarado se deniega
  Cuando hago GET /actuator/env sin Authorization
  Entonces la respuesta es 401 y no se expone ninguna configuración

@seguridad @integracion
Escenario: SEC-15 los errores de seguridad no filtran detalles internos
  Cuando recibo un 401 o un 403
  Entonces el cuerpo es problem+json con título genérico
  Y no incluye trazas, nombres de clases ni valores del token

@seguridad @integracion
Escenario: SEC-16 las respuestas llevan cabeceras de seguridad
  Cuando hago cualquier pedido
  Entonces la respuesta trae X-Content-Type-Options: nosniff, Cache-Control: no-store y Content-Security-Policy

@seguridad @unit @integracion
Escenario: SEC-17 una Idempotency-Key ajena no revela el video de otro
  Dado que usr_lucia subió un video con la Idempotency-Key k-1
  Cuando usr_mateo hace POST /videos con la misma Idempotency-Key k-1
  Entonces la respuesta es 409
  Y no incluye el videoId de Lucía

@seguridad @e2e
Escenario: SEC-18 el flujo completo con un token real de Keycloak
  Dado el ecosistema levantado y un token pedido a Keycloak para lucia
  Cuando hago POST /videos con ese token
  Entonces el video llega a PUBLISHED y Loki devuelve el flujo por correlationId
```

## Trazabilidad
- SEC-04 y el campo opcional `creatorId` cumplen la decisión de compatibilidad de Laura (ADR 0013, punto 5).
- SEC-10 y SEC-17 son reglas de dominio y de aplicación: se prueban con puertos en memoria antes de probarse por HTTP.
- SEC-18 es el único que usa el Keycloak real; los demás firman sus propios tokens.
