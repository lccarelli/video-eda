# Eventos: contratos y schemas

> **Estado:** borrador para revisión · **Última actualización:** 2026-10-12 (regla de publicación aplicada, retención y `feed.follow`)

Contrato de los eventos que **publican** los contextos. Un archivo AsyncAPI por contexto productor y un JSON Schema por evento. El respaldo de cada decisión está en las notas 01, 12 y 13 y en los ADR 0002, 0003 y 0008.

```
specs/03-events/
  asyncapi/
    videos.yaml          canales videos.video y videos.processing
    interactions.yaml    canal interactions.comment
    feed.yaml            canal feed.follow
  schemas/
    cloudevent-envelope.json    atributos comunes (structured mode)
    video-published.json   video-deleted.json   process-video.json
    comment-posted.json    comment-edited.json  comment-deleted.json
    creator-followed.json  creator-unfollowed.json
```

## Regla de publicación

> **Un evento se publica en Kafka solo si otro contexto lo consume.** (ADR 0008: menos es más.)

Lo que un contexto hace con su propia base no necesita un topic.

## Qué se publica

| Topic | Key | Evento (`type`) | Productor | Consumidores | Particiones |
|---|---|---|---|---|---|
| `videos.video` | `videoId` | `dev.videoeda.videos.video-published` | Videos | Interactions, Feed, Notifications | 3 |
| `videos.video` | `videoId` | `dev.videoeda.videos.video-deleted` | Videos | Interactions, Feed, Notifications | 3 |
| `videos.processing` | `videoId` | `dev.videoeda.videos.process-video` | Videos | Worker de Videos (share group) | 3 |
| `interactions.comment` | `commentId` | `dev.videoeda.interactions.comment-posted` | Interactions | Notifications | 3 |
| `interactions.comment` | `commentId` | `dev.videoeda.interactions.comment-edited` | Interactions | Notifications | 3 |
| `interactions.comment` | `commentId` | `dev.videoeda.interactions.comment-deleted` | Interactions | Notifications | 3 |
| `feed.follow` | `followerId:creatorId` | `dev.videoeda.feed.creator-followed` | Feed | Notifications | 3 |
| `feed.follow` | `followerId:creatorId` | `dev.videoeda.feed.creator-unfollowed` | Feed | Notifications | 3 |

Los consumidores y lo que esperan de cada evento no se listan en el AsyncAPI: se verifican con Pact (ADR 0006).

## Qué **no** se publica (aplicado)

> **Aplicado el 2026-10-12** por decisión de Laura ("sí" a la regla de publicación). Antes figuraba como "a confirmar, no aplicada".

Estos eventos del event storming **no tienen consumidor en otro contexto**: son eventos de dominio internos y no tienen topic ni contrato AsyncAPI.

| Evento | Por qué no se publica | Cómo se resuelve ahora |
|---|---|---|
| `VideoRated` | Interactions guarda y calcula las puntuaciones en su base; nadie más las consume | Estado en la tabla de puntuaciones, PK `videoId:raterId`; el conflicto concurrente se resuelve con `sequence` en la fila (RAT-01, RAT-03, RAT-04) |
| `VideoViewed` | Sin consumidor ni escenario | Sin topic `interactions.view` |
| `FeedEntryAdded` | Sin consumidor; el feed es el read model | Filas de la tabla del feed, única por `(followerId, videoId)` (FEED-02, FEED-10) |
| `VideoUploaded`, `VideoProcessed`, `VideoProcessingFailed` | Pasos internos de la saga de Videos (el worker y Videos comparten servicio) | Estado del process manager |
| `NotificationRequested`, `NotificationSent`, `NotificationFailed` | Estado interno de Notifications (ADR 0008) | Columna de estado de la tabla de notificaciones |

**Resultado:** quedan **tres topics de dominio** (`videos.video`, `interactions.comment` y `feed.follow`) más la cola `videos.processing`. No existen `interactions.rating`, `interactions.view` ni `feed.entry`.

**Consecuencia sobre el orden:** lo que antes se resolvía "por el orden de Kafka" dentro de un mismo servicio (puntuaciones, y follow/unfollow del mismo par **dentro de Feed**) se resuelve en la base local con la columna `sequence` de la fila. Para esos casos ya no hay un test de orden por key: el test pasa a ser de concurrencia sobre la fila. El orden por key **vuelve a importar** en `feed.follow`, pero para el consumidor (Notifications): follow y unfollow del mismo par deben aplicarse en orden (NOT-14, NOT-15).

### Resuelto: `feed.follow` (opción a, Laura, 2026-10-12)
NOT-01 ("notificar a los seguidores de una publicación") necesita saber quién sigue a quién. La regla de publicación se aplica en las dos direcciones: si Notifications consume los follows, entonces `CreatorFollowed` y `CreatorUnfollowed` **se publican**. Alternativas descartadas: (b) consultar a Feed por HTTP (acoplamiento síncrono, rompe la autonomía) y (c) que Feed arme la lista de destinatarios en un evento (un evento de tamaño variable por publicación).

Consecuencias: Feed pasa a ser productor y provider de contrato (Pact, nota 10); `Follow` necesita publicación atómica, por lo que usa outbox (ADR 0004, 0009); Notifications mantiene su propia lista de seguidores como read model desde este topic.

## Retención

| Topic | `retention.ms` | Razón |
|---|---|---|
| `videos.video` | `-1` (infinita) | Es la fuente desde la que Interactions, Feed y Notifications reconstruyen sus read models (nota 14); con los 7 días por defecto de Kafka 4.3 no se podría |
| `interactions.comment` | `-1` (infinita) | Igual para el read model de Notifications y como respaldo de la reproyección (nota 14) |
| `feed.follow` | `-1` (infinita) — **a confirmar por Laura** | Es la fuente de la lista de seguidores de Notifications; con la retención por defecto no se podría reconstruir (mismo criterio que la nota 14). Derivado de la opción (a), no decidido explícitamente |
| `videos.processing` | default (7 días) | Cola de trabajo, no fuente de read models |
| DLQ | default | Operación manual |

Decisión de Laura, 2026-10-12. Mantiene `cleanup.policy=delete`: con `retention.ms=-1` nada se borra por tiempo. Sin experimento todavía.

## Identificadores
Decisión de Laura (2026-10-12), estudio en la [nota 17](../../docs/study/17-identificadores-y-nombres.md); los alias de los ejemplos están en [`ids-de-ejemplo.md`](../01-domain/ids-de-ejemplo.md).

| Campo | Formato | Quién lo genera |
|---|---|---|
| `id` del evento | `evt_<UUIDv7>`, estable en reenvíos | El productor |
| `subject` | El id del agregado, sin tipo adicional: `vid_<UUIDv7>`, `cmt_<UUIDv7>` o el compuesto `usr_<UUIDv7>:usr_<UUIDv7>` (`followerId:creatorId`) | El productor |
| `correlationid` | `cor_<UUIDv7>` | El borde de la API |
| `videoId`, `commentId`, `authorId`, `creatorId`, `followerId` en `data` | `vid_`, `cmt_`, `usr_` + UUIDv7 | El servicio dueño, antes del primer INSERT |
| Key del topic | El mismo texto que el `subject` | — |

Los schemas imponen el formato con `pattern` (versión 7, variante RFC 9562): un `videoId` con prefijo `cmt_` o un UUID versión 4 se rechazan. El `source` sigue siendo `/<contexto>`.

## Convenciones
- **Modo structured** de CloudEvents: todo el evento va en el valor del mensaje, con `contentType: application/cloudevents+json`.
- **`type`:** `dev.videoeda.<contexto>.<evento>`, sin sufijo de versión; el primer cambio incompatible agrega `.v2` (ADR 0002, nota 12).
- **Extensiones:** `sequence` (obligatoria) y `correlationid` (opcional). `causationid` diferida (ADR 0008).
- **Sin `additionalProperties: false`:** los consumidores toleran campos desconocidos. Es una decisión para el lector tolerante; Zalando #210 recomienda lo contrario, y el efecto sobre la compatibilidad queda para el contract testing (nota 12).
- **Payload sin contenido binario:** el video viaja por referencia (`storageRef`), nunca por Kafka.
- **`replicas: 1`** en los bindings es el valor del entorno de Compose (ADR 0007); el objetivo documentado es 3 (nota 13, T6).

## Cómo se valida
Corrido el 2026-10-12:
- **AsyncAPI:** `asyncapi validate` (CLI oficial) sobre `videos.yaml`, `interactions.yaml` y `feed.yaml` en 3.1.0: **válidos, sin errores ni advertencias de gobernanza**.
- **JSON Schema:** los nueve schemas cumplen Draft 7, los ocho ejemplos de mensaje validan contra su schema, y para cada mensaje fallan los casos negativos (evento sin `sequence`, y `type` con sufijo `.v1`).

## No verificado
- **Formato de ids:** los `pattern` y los ejemplos se validaron con jsonschema; que `Uuid.generateV7()` de Kotlin sea monótono dentro del mismo milisegundo no está verificado (experimento ID-03, nota 17).
- **Content type en Kafka:** que `application/cloudevents+json` sea el valor correcto del header en structured mode proviene del estudio de la nota 01, no se verificó de nuevo contra la especificación del binding de Kafka de CloudEvents.
- **`$id` y `$ref` entre schemas:** URN `urn:videoeda:schema:<nombre>` (ADR 0002, enmienda 2026-10-16); verificado con `networknt 3.0.8`.
- **Referencias entre archivos:** la CLI resolvió los `$ref` relativos a `../schemas/`; otras herramientas (generadores de código, documentación) no se probaron.
- **Documentos de solo consumidor:** no se escribieron; no verifiqué si AsyncAPI 3.1.0 permite referenciar mensajes de otro archivo en un canal.
- **Pact contra estos schemas:** el contrato de consumidor y la referencia `asyncapi` de Pact V4 no se probaron.
- **Nada se ejecutó contra un broker.**
