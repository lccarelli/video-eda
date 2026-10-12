# Glosario

> **Estado:** borrador · **Última actualización:** 2026-10-12

## Dominio

| Término | Definición | Contexto |
|---|---|---|
| **Creator** | Persona que sube y publica videos | Videos |
| **Viewer** | Persona que mira, puntúa, comenta o sigue | Interactions, Feed |
| **Video** | Pieza publicada por un creator; estados `UPLOADED`, `PROCESSING`, `PUBLISHED`, `FAILED`, `DELETED` | Videos |
| **Publicación** | Transición de un video a `PUBLISHED`; es el pivote de la saga | Videos |
| **Rating** | Puntuación de un viewer a un video; una vigente por par `videoId:raterId` | Interactions |
| **Comment** | Comentario de un viewer a un video; único agregado con event sourcing (propuesta) | Interactions |
| **View** | Reproducción registrada | Interactions |
| **Follow** | Relación de un viewer que sigue a un creator; agregado de Feed que publica `CreatorFollowed` y `CreatorUnfollowed` en `feed.follow`, que Notifications consume | Feed |
| **Feed** | Lista ordenada de videos publicados por creators que el viewer sigue | Feed |
| **Notification** | Aviso a un usuario por un hecho de interés; estados `REQUESTED`, `SENT`, `FAILED`, `CANCELLED` | Notifications |

## Técnico

| Término | Definición | Nota de estudio |
|---|---|---|
| **Envelope** | Metadatos estándar del evento (CloudEvents) | 01 |
| **Payload** | Datos del evento, descritos por un schema | 01 |
| **Key** | Identificador del agregado; fija la partición y el orden | 02 |
| **Inbox** | Tabla que registra eventos ya procesados para deduplicar | 03 |
| **Outbox** | Tabla que guarda eventos a publicar en la misma transacción que el estado | 05 |
| **CDC** | Captura de cambios de la base para publicar el outbox | 05 |
| **Share group** | Grupo de consumers que reparte registros sin dueño de partición | 07 |
| **DLQ** | Destino de mensajes que agotaron reintentos o son inválidos | 04 |
| **Pivote** | Paso de una saga desde el cual ya no se compensa | 08 |
| **Process manager** | Componente que coordina los pasos de una saga con estado persistido | 08 |
| **Semantic lock** | Estado que impide operaciones sobre un agregado en transición | 08 |
| **Contrato** | Acuerdo verificable entre productor y consumer sobre un evento | 01 |
| **Topic** | Canal con nombre al que se publican eventos; en Kafka, un log dividido en particiones | 13 |
| **Partición** | Unidad de paralelismo y de orden de un topic; el orden solo existe dentro de una | 02, 13 |
| **Avro** | Formato binario de serialización con schema en JSON; no se usa en este proyecto | 12 |
| **JSON Schema** | Descripción en JSON de la estructura de un payload JSON; es el formato elegido | 12 |
| **Schema Registry** | Servicio que guarda schemas y verifica compatibilidad; no se usa en este proyecto | 12 |

## Modelado táctico (DDD, nota 15)

| Término | Definición | Nota de estudio |
|---|---|---|
| **Aggregate** | Grupo de entidades y value objects con una raíz; unidad de consistencia transaccional. Su identidad es la key de su topic | 15 |
| **Invariant** | Regla de negocio que debe cumplirse siempre dentro de la frontera de un agregado | 15 |
| **Value object** | Objeto sin identidad, definido por sus atributos e inmutable (`StarRating`, `CommentText`) | 15 |
| **Domain event** | Hecho que ocurrió en el agregado, interno al contexto; distinto del evento publicado (CloudEvent) | 15 |
| **Published language** | Formato público y documentado de lo que un contexto publica (CloudEvents + AsyncAPI + JSON Schema) | 15 |
| **ACL** (Anti-Corruption Layer) | Capa que traduce el modelo de otro contexto al propio; acá, liviana: el consumidor lo guarda con su nombre | 15 |

## CQRS y event sourcing (notas 14 y 16)

| Término | Definición | Nota de estudio |
|---|---|---|
| **Command** | Intención de cambiar el estado (`PostComment`); la valida un agregado, que la acepta o la rechaza | 14 |
| **Query** | Lectura que no modifica estado; responde desde un read model | 14 |
| **Read model** | Tabla derivada de eventos con la forma que necesita la lectura; tiene un dueño y se reconstruye | 14 |
| **Projection** | Proceso (o su resultado) que mantiene un read model a partir de eventos; idempotente | 14 |
| **Reprojection** | Vaciar un read model y reconstruirlo reprocesando los eventos desde el principio | 14 |
| **Event store** | Tabla append-only con los eventos de cada agregado; es la fuente de verdad del agregado (`comment_events`) | 16 |
| **Stream** | Secuencia ordenada de los eventos de un agregado (`stream_id` = `commentId`) | 16 |
| **Expected version** | Versión que el escritor espera encontrar al agregar un evento; si no coincide, hay conflicto | 16 |
| **Aggregate id** | Identificador del agregado: `<prefijo>_<UUIDv7>`; es a la vez el `subject`, la key del topic y el `stream_id` | 17 |
| **Stream id** | Id del stream en el event store; **es** el id del agregado, no existe un segundo identificador | 17 |
| **Event id** | `id` del CloudEvent: `evt_<UUIDv7>`, estable en reenvíos; con `source` es la clave de deduplicación. No es el offset, ni la `sequence`, ni el `global_seq` | 17 |
| **Id compuesto** | Par de ids completos separados por `:`, por ejemplo `followerId:creatorId`; el `:` no pertenece al alfabeto de un id | 17 |
| **Upcaster** | Función que convierte un evento guardado en forma vieja a la actual al leerlo, sin reescribirlo | 16 |

## Convenciones de nombre
- **Eventos:** `PascalCase` en pasado (`VideoPublished`).
- **`type` de CloudEvents:** `dev.videoeda.<contexto>.<evento-kebab>`, por ejemplo `dev.videoeda.videos.video-published` (el dominio `videoeda.dev` es provisorio). **Sin versión mientras no haya un cambio incompatible**; el primero agrega `.v2` (decisión de Laura, 2026-10-12; práctica de Zalando #213). La ausencia de sufijo equivale a la versión 1.
- **Topics:** `<contexto>.<agregado>`, por ejemplo `videos.video`, `interactions.comment`; un topic por agregado, con todos sus tipos de evento (ADR 0003). DLQ con sufijo `.dlq`. **Sin versión en el nombre** (decisión de Laura, 2026-10-12); la versión vive en el `type` del evento.
- **Identificadores:** `<prefijo>_<UUIDv7>` como texto (`vid`, `cmt`, `usr`, `ntf`, `evt`, `cor`), generado por el servicio dueño. Decidido el 2026-10-12 (nota 17, ADR 0002 propuesto). Los alias de los ejemplos están en [`ids-de-ejemplo.md`](ids-de-ejemplo.md).
