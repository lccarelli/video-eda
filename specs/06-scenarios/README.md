# Escenarios de aceptación

> **Estado:** borrador para revisión · **Última actualización:** 2026-10-12

Criterios de aceptación en Given/When/Then, uno por comportamiento observable. Cada escenario apunta a la garantía (`../07-nfr/garantias.md`) y al ADR que lo respalda, y dice **con qué tipo de test se verifica**.

## Archivos

| Archivo | Proceso | Contextos |
|---|---|---|
| [01-publicacion-de-video](01-publicacion-de-video.md) | Subir, procesar y publicar (saga) | Videos |
| [02-puntuaciones](02-puntuaciones.md) | Puntuar un video | Interactions |
| [03-comentarios](03-comentarios.md) | Comentar, editar y borrar | Interactions, Notifications |
| [04-seguimiento-y-feed](04-seguimiento-y-feed.md) | Seguir creators, armar el feed y publicar los follows | Feed |
| [05-notificaciones](05-notificaciones.md) | Avisar a usuarios; lista de seguidores desde `feed.follow` | Notifications |
| [06-contratos-y-garantias](06-contratos-y-garantias.md) | Contratos, key, DLQ y configuración | Transversal |
| [07-zombies](07-zombies.md) | Cero, uno y muchos sobre todos los procesos (19 escenarios nuevos) | Todos |
| [08-cqrs](08-cqrs.md) | Lado query, consistencia eventual y reproyección (QRY-01 a QRY-06) | Videos, Interactions, Feed |
| [09-agregados](09-agregados.md) | Frontera e invariantes de cada agregado (AGG-01 a AGG-16) | Todos |
| [10-event-sourcing](10-event-sourcing.md) | Event store de `Comment`: versión esperada, historia, reproyección (ES-01 a ES-10) | Interactions |
| [11-resiliencia](11-resiliencia.md) | Hueco de `sequence`, redrive en orden, dependencia caída, picos y replay (RES-01 a RES-09; ADR 0011 aceptado) | Transversal |
| [12-seguridad](12-seguridad.md) | Autenticación y autorización de la API de Videos (SEC-01 a SEC-18; ADR 0013) | Videos |

## Cobertura por tests (se actualiza al implementar)

| Escenarios | Nivel | Dónde |
|---|---|---|
| AGG-04, AGG-05, AGG-06, PUB-02, PUB-10 | `@unit` del agregado `Video` | `services/videos/.../domain/VideoTest.kt` (8 pruebas, 2026-10-17) |
| PUB-01 (sin atomicidad), PUB-02, PUB-04 (sin DLQ), PUB-08, PUB-10, AGG-02 | `@unit` de casos de uso con puertos en memoria; contrato contra los JSON Schema reales | `services/videos/.../application/` (12 pruebas, 2026-10-17). La atomicidad y la asignación de `sequence` se probaron aparte (fila siguiente) |
| PUB-01 (atomicidad), PUB-08 (carrera), PUB-10, concurrencia de `CompleteVideoProcessing`, `sequence` contigua por stream | `@integracion` contra PostgreSQL 18.6 real (Testcontainers, Flyway, `JdbcClient`) | `services/videos/.../infrastructure/VideoPersistenceTest.kt` (10 pruebas, 2026-10-17). Sin probar: la caída real del proceso |
| PUB-01 (publicación), PUB-05, PUB-06 (lado productor), G1 (orden por key), G2, G3, Kafka caído, una sola instancia publicadora, logs con `correlationId` | `@integracion` contra PostgreSQL 18.6 y Kafka 4.3.1 reales | `services/videos/.../infrastructure/OutboxPublisherTest.kt` (7 pruebas, 2026-10-17). El inbox del consumer (PUB-06, lado Feed) y la caída real con `kill -9` **siguen sin probar** |
| ES-01 a ES-10, RES-01 a RES-05, RES-07 | Spikes (clientes planos, broker y base reales) | `spikes/event-store-check`, `spikes/resilience-check` |

El resto de los escenarios todavía no tiene test del servicio.

## Convenciones
- **ID:** `<PROCESO>-NN` (por ejemplo `PUB-03`); estable, no se renumera.
- **Etiquetas:** `@G1`…`@G8` (garantía), `@ADR-000N`, `@zero` / `@one` / `@many` (ZOMBIES, ver `07-zombies.md`), y el tipo de verificación: `@unit`, `@integracion` (broker real), `@contrato`, `@experimento` (aún sin forma de probar).
- **Estado de decisión:** `@pendiente` marca un escenario cuyo resultado esperado todavía no está decidido; no se implementa hasta resolverlo.
- **Lenguaje:** pasos de negocio, sin nombres de clases. Identificadores técnicos (eventos, topics) en inglés.
- Cada escenario es independiente: no depende del resultado de otro.
- **Orden de implementación:** Zero, One, Many (`07-zombies.md`). Los escenarios nuevos de ese archivo mantienen el prefijo de su proceso y siguen la numeración (por ejemplo `RAT-08`).

## Ids de los escenarios

Los escenarios no usan ids cortos (`c-1`, `v-1`, `u-42`). Usan **alias legibles** con el prefijo del formato real: `usr_ana`, `usr_lucia` (creator), `vid_paella`, `cmt_gracias`, `evt_alfa`, `ntf_alfa`, `idk_subida`.

- **Un alias, una entidad:** `usr_ana` es siempre el mismo viewer en todo el repositorio, y `usr_lucia` siempre la creator.
- **Id real:** cada alias corresponde a un id `<prefijo>_<UUIDv7>` fijo en [`../01-domain/ids-de-ejemplo.md`](../01-domain/ids-de-ejemplo.md). Los payloads, AsyncAPI y JSON Schema usan el id real; los Gherkin usan el alias.
- **Claves compuestas:** `{followerId}:{creatorId}` se escribe `usr_ana:usr_lucia`; la puntuación, `vid_paella:usr_ana`.
- **Muchos:** `usr_viewer-01` a `usr_viewer-50` (RAT-10); sus ids reales se generan en el test.
- **No son ids:** el sufijo `.v2` del `type` y "versión N" del stream.
- **Alias nuevo:** se agrega primero a la tabla de `ids-de-ejemplo.md`.

## Tipos de verificación

| Etiqueta | Qué significa |
|---|---|
| `@unit` | Lógica de un servicio sin broker |
| `@integracion` | Contra un broker Kafka 4.3.1 y/o una base real (Testcontainers) |
| `@contrato` | Pact o compatibilidad de schema |
| `@experimento` | Requiere un spike para saber cómo probarlo |

## Resumen de cobertura

| Garantía | Escenarios |
|---|---|
| G1 Orden por agregado | PUB-02, RAT-04, COM-03, COM-04, FEED-03, NOT-14, AGG-01, AGG-11, ES-02, ES-03, ES-05, ES-06, ES-10 |
| G2 Sin pérdida estado-evento | PUB-05, FEED-14, ES-08 |
| G3 At-least-once | PUB-06, ES-08 |
| G4 Efecto único | RAT-03, COM-07, FEED-04, NOT-02, NOT-15, QRY-04, AGG-16, ES-08 |
| G5 Nada se pierde en silencio | PUB-04, COM-06, NOT-03, CTR-04 |
| G6 Contratos verificados | CTR-01, CTR-02, CTR-03 |
| G7 Key estable | CTR-05 |
| G8 Read model reconstruible | QRY-02, QRY-03, QRY-04, ES-07 |

Los archivos 08 a 10 suman **32 escenarios nuevos** (QRY 6, AGG 16, ES 10), todos propuestos a partir de las notas 14 a 16. La opción (a) de `feed.follow` agregó FEED-14 y NOT-13 a NOT-17: **112 escenarios** en total.

## Decisiones tomadas a partir de los escenarios (2026-10-12)
| Tema | Decisión | Quién | Escenarios |
|---|---|---|---|
| Follow con publicaciones anteriores | Sí las trae; cuántas (N) sin definir | Laura | FEED-06, FEED-12, FEED-13 |
| Unfollow | Quita las entradas de ese creator | Laura (por simetría con FEED-06) | FEED-07 |
| Video borrado con notificaciones pendientes | Se cancelan (`CANCELLED`) y se conservan; no se envían | Laura | NOT-05, NOT-10, NOT-11, NOT-12 |
| Falla de Feed tras el pivote | No se compensa (ya decidido en nota 08); falta el experimento | Nota 08 | PUB-09 |
| Event sourcing | Solo en `Comment`; es una elección didáctica, no una necesidad del dominio | Laura | ES-01 a ES-10 |
| Dónde vive el event store | Base del servicio (PostgreSQL), no Kafka | Laura | ES-02, ES-03 |
| Proyección de comentarios | En la misma transacción que el append | Laura | ES-03 |
| Retención de `videos.video` e `interactions.comment` | `retention.ms=-1` para poder reconstruir read models | Laura | QRY-02 a QRY-04 |
| Lista de seguidores de Notifications | Notifications consume `feed.follow` (opción a); `CreatorFollowed` y `CreatorUnfollowed` se publican | Laura | NOT-01, NOT-08, NOT-13 a NOT-17, FEED-01, FEED-03, FEED-14 |
| Retención de `feed.follow` | `retention.ms=-1`, derivada de la opción a; **a confirmar** | Derivada de la opción a | NOT-13, NOT-14 |
| Borrado de comentarios | Lógico; el texto queda en el historial | Laura | ES-05 |

## Todavía abierto
1. ¿Cuántas publicaciones anteriores trae un follow (valor de N)?
2. ¿Se puede editar un comentario ya borrado? Se asumió que no (COM-05).
3. ¿Cuántos reintentos y cuánta espera antes de la DLQ? Los valores 3 y 500 ms son provisorios (nota 04).
4. Nombre de las DLQ cuando varios consumers leen el mismo topic (ADR 0005).
5. Rango válido de la puntuación (AGG-09) y comportamiento del follow repetido (AGG-12).
6. Cómo se prueba el upcasting al aparecer el primer `.v2` (`10-event-sourcing.md`, "No cubierto").
| SEC-01 a SEC-17 | `@integracion` con la app completa (tokens firmados por una clave de prueba y el mismo validador de producción) y `@unit` de casos de uso | `services/videos/.../web/VideosSecurityTest.kt` (18 pruebas) y `VideoUseCasesTest.kt` (2026-10-18) |
| SEC-18, SEC-01, SEC-10, SEC-12 | `@e2e` con Keycloak real | `deploy/dev/smoke.sh` |
