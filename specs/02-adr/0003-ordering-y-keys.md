# ADR 0003: Ordering y keys

> **Estado:** Aceptado el 2026-10-12 por Laura · **Última actualización:** 2026-10-12 · **Enmendado por:** regla de publicación y retención (2026-10-12) y formato de ids (nota 17, 2026-10-12)

## Contexto
Kafka garantiza orden total solo dentro de una partición. El orden relevante para el negocio es **por agregado**, no global (nota 02).

## Decisión
1. **La key de cada topic es el identificador del agregado** cuyo orden importa.
2. El producer es idempotente (default en Kafka 4.x) y **está prohibido** configurar `partitioner.class` y `partitioner.ignore.keys`.
3. Todo evento que muta un agregado lleva `sequence` (ADR 0002).
4. Los consumers con orden por key no usan pool de threads sin particionar por key.
5. Cambiar la cantidad de particiones de un topic requiere un ADR y un plan de migración.

### Nombres de topics
- Formato `<contexto>.<agregado>`: **un topic por agregado**, con todos sus tipos de evento (`CommentPosted`, `CommentEdited`, `CommentDeleted` en `interactions.comment`).
- **Sin versión en el nombre.** Decisión de Laura (2026-10-12). Respaldo: CloudEvents (un cambio incompatible cambia el `type`) y Kleppmann (varios tipos por topic conservan el orden). La guía de Confluent sí sugiere versión en el topic, pero solo ante cambios incompatibles (nota 12). La versión vive en el `type` del CloudEvent (ADR 0002).
- Una DLQ por contexto consumidor, con sufijo `.dlq` (`interactions.dlq`, `feed.dlq`, `notifications.dlq`) más `videos.processing.dlq` del worker (ADR 0008).
- Razón del topic por agregado: los eventos de un mismo agregado comparten partición y key, por lo que mantienen el orden entre sí (nota 02). Con un topic por tipo de evento ese orden se perdería.
- **Cambio incompatible:** se resuelve con un `type` nuevo en el mismo topic, emitiendo ambos durante la migración (ADR 0002). Recurso de último caso: un topic nuevo con otro nombre y un ADR de migración.
- **Varios consumidores por topic:** cada contexto lee con su propio consumer group o share group; la ausencia de versión en el nombre no los limita.

### Formato de las keys
La key es el **id del agregado con su prefijo**, como texto: `vid_<UUIDv7>` en `videos.video` y `videos.processing`, `cmt_<UUIDv7>` en `interactions.comment`. Es el mismo valor que el `subject` del evento y el `stream_id` del event store (nota 17, R3 y R4). Los ids compuestos son `{a}:{b}` con ambos ids completos: `feed.follow` usa `{followerId}:{creatorId}` (`usr_<UUIDv7>:usr_<UUIDv7>`, follower primero) y la puntuación, aunque no se publica, `{videoId}:{raterId}`. El `:` no pertenece al alfabeto de un id, así que el par se separa sin ambigüedad.

### Tabla de keys

| Topic | Key | Razón | Retención |
|---|---|---|---|
| `videos.video` | `videoId` | Ciclo de vida del video | `retention.ms=-1` |
| `interactions.comment` | `commentId` | `CommentPosted` antes que `CommentDeleted` | `retention.ms=-1` |
| `feed.follow` | `followerId:creatorId` | `CreatorFollowed` antes que `CreatorUnfollowed` del mismo par; lo consume Notifications | `retention.ms=-1` *(a confirmar por Laura)* |
| `videos.processing` | `videoId` | Una tarea por video | default |

> **Enmienda (2026-10-12):** la **regla de publicación** (un evento se publica solo si otro contexto lo consume; `03-events/README.md`, aprobada por Laura) quitó de esta tabla `interactions.rating`, `interactions.view` y `feed.entry`. Sus claves naturales (`videoId:raterId`) pasan a ser PK de tablas locales y el orden se resuelve con `sequence` en la fila, no con el orden de Kafka. **`feed.follow` vuelve** por decisión de Laura (opción a, 2026-10-12): Notifications necesita los follows y la regla obliga a publicarlos; el seguimiento mantiene además su PK `followerId:creatorId` en la tabla de Feed.

**Retención:** `retention.ms=-1` en `videos.video`, `interactions.comment` y `feed.follow` (esta última **a confirmar por Laura**: derivada de la opción a, mismo criterio) porque los read models de otros contextos se reconstruyen desde ellos (nota 14); con el default de 7 días de Kafka 4.3 no se podría. `cleanup.policy` sigue en `delete`. Decisión de Laura, 2026-10-12.

### Orden entre topics
No existe orden entre topics. Para el caso `CommentPosted` antes de `VideoPublished`, la política por defecto es **retry bloqueante corto y luego DLQ** (nota 04), con la alternativa de placeholder si la medición muestra que ocurre seguido.

## Consecuencias
- Throughput por agregado limitado a una partición; aceptable en este dominio.
- Un mensaje venenoso frena su partición en consumer groups con orden; por eso el retry es bloqueante con tope.
- Mucha carga en un video viral genera una partición caliente (hot key); se mide y, si hace falta, se reconsidera `videoId`.
- La retención infinita hace crecer el disco sin tope en `videos.video`, `interactions.comment` y `feed.follow`; aceptable en un ejemplo, a revisar si hay carga real.

## Alternativas
| Opción | Por qué no |
|---|---|
| Topic único con key de contexto | Mezcla agregados y limita la paralelización |
| Orden global (una partición) | No escala y no es necesario |
| Partitioner propio | Rompe la garantía por key; prohibido por regla |

## Verificación
- Test de orden por key contra broker real: publica N eventos de un mismo agregado y verifica el orden de consumo.
- Test que falla si el producer define `partitioner.class` o desactiva la idempotencia.
- Escenario Given/When/Then para `CommentPosted` y `CommentDeleted` fuera de orden.

## Pendiente
- Cambio de particiones y rebalances sobre el orden: no leído en documentación oficial (nota 02).
- Cantidad inicial de particiones por topic.

## Respaldo
Nota 02 (ordering), nota 04 (reintentos), nota 06 (Kafka 4.x).
