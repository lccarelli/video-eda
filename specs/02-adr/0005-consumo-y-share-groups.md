# ADR 0005: Consumo, reintentos y share groups

> **Estado:** Aceptado el 2026-10-12 por Laura · **Actualizado el 2026-10-12 por el ADR 0008 (aprobado):** Notifications vuelve a un solo consumer group; share group solo para el worker de video.

## Contexto
El outbox entrega at-least-once, así que los consumers reciben duplicados. Según el tipo de trabajo hay dos modelos de consumo: consumer group con orden por key, y share group sin orden pero con paralelismo mayor que las particiones (notas 02, 03, 04, 07).

## Decisión

### Qué modelo usa cada consumidor

| Consumidor | Modelo | Reintento | DLQ |
|---|---|---|---|
| Interactions (comentarios, puntuaciones) | Consumer group, `group.protocol=consumer` | Bloqueante, tope de 3 intentos y 500 ms | `interactions.dlq` |
| Feed (`videos.video`; sus follows y entradas son estado local) | Consumer group, `group.protocol=consumer` | Bloqueante con tope | `feed.dlq` |
| Videos (worker de procesamiento) | Share group | RELEASE hasta el límite; RENEW en procesos largos | `videos.processing.dlq` |
| Notifications (`videos.video`, `interactions.comment` y `feed.follow`; crear, cancelar y enviar) | Consumer group, `group.protocol=consumer` | Bloqueante con tope; el envío es una llamada simulada | `notifications.dlq` |

### Reglas comunes
1. **Inbox por `source` + `id`** en la misma transacción que el read model.
2. Los consumers no asumen exactly-once.
3. Los errores se clasifican: los permanentes (schema inválido) van directo a DLQ y no se reintentan.
4. Todo reintento usa backoff exponencial con jitter y un tope.
5. Toda DLQ tiene dueño, alerta y procedimiento de reproceso; conserva `eventId`, causa, intentos y topic de origen.
6. Reprocesar desde la DLQ pasa por el inbox.

### Reglas de share groups
7. `share.auto.offset.reset=earliest` explícito en cada grupo.
8. DLQ propia: se publica antes del REJECT y se alerta sobre registros archivados.
9. Procesos largos usan RENEW con `share.acquire.mode=record_limit` y `max.poll.records=1`. **Verificado en el spike S2 (2026-10-12):** `ShareAcknowledgment.renew()` dentro del listener de Spring **no** mantiene el lock; el worker debe usar el cliente directo (`acknowledge(RENEW)`, `commitSync()`, `poll()` entre renovaciones) o un lock suficientemente largo. `deliveryCount()` está disponible para decidir el envío a la DLQ.
10. En Spring: modo `MANUAL`, o un `ShareConsumerRecordRecoverer` propio que publique en la DLQ y devuelva REJECT; la integración con DLQ no viene incluida.
11. No usar share groups donde el orden por key importe.

### Aclaración del ADR 0009 (event sourcing de `Comment`, propuesto)
El lado consumidor **no cambia**: aunque `Comment` se persista como event store, quien lo consume en otro contexto recibe CloudEvents con `id` y `sequence` y aplica el mismo inbox por `source` + `id` y el mismo guard por `sequence` (COM-04, COM-13). Que el productor use `version` del stream como `sequence` no altera la regla de descarte por `sequence` menor.

### Por qué Notifications no usa share group
Los escenarios NOT-05 y NOT-12 mostraron que `VideoDeleted` debe verse **después** de `VideoPublished` del mismo video, y un share group no garantiza orden por key (nota 07). Con un solo consumer group ordenado por key el problema no existe. El envío es una llamada simulada y no justifica una cola aparte (ADR 0008).

## Consecuencias
- Interactions y Feed tienen orden y ven bloqueada su partición ante un mensaje venenoso, acotado por el tope.
- Solo el worker de video escala por encima de las particiones y pierde orden; es idempotente por `videoId`.
- Un envío lento de Notifications retrasa su partición; el tope de reintentos lo acota.
- Hay que armar a mano la deserialización (sin converters) y la DLQ del share consumer del worker.

## Alternativas
| Opción | Por qué no |
|---|---|
| Retry topics en todos los consumers | Pierde orden por key donde importa (nota 04) |
| Cola externa para trabajo independiente | Otro sistema que operar; Kafka 4.3 ya cubre el caso |
| Consumer group clásico en lugar de KIP-848 | Se mantiene como fallback si aparece un problema con el protocolo nuevo |

## Verificación
- Test de duplicado: el mismo `id` dos veces produce un solo efecto. El worker lo cumple por estado del video (no por inbox); los consumer groups de Interactions, Feed y Notifications siguen pendientes.
- Test del worker: lock expirado con y sin RENEW (caso del video transcodificado dos veces, nota 07). **Verificado el 2026-10-17** (`ProcessVideoShareWorkerTest`, PUB-03): sin RENEW el video se transcodifica dos veces y `VideoPublished` sale una sola; con RENEW, una.
- Escenario: evento con schema inválido va directo a DLQ con causa y alerta. **Verificado el 2026-10-17** en el worker de Videos; la "alerta" es hoy un log `ERROR` con `alert=true`, sin sistema de alertas detrás.
- Test: un registro que agota entregas queda en la DLQ propia y no solo archivado. **Verificado el 2026-10-17** (PUB-04): el worker cuenta las entregas con `deliveryCount()` y a la tercera publica en `videos.processing.dlq` con causa, intentos, `eventId` y origen, antes del `REJECT`.

## Pendiente
- Con 3 particiones en `videos.processing`, un registro con el lock vencido solo vuelve al mismo worker mientras haya menos workers que particiones (nota 07, hallazgo del 2026-10-17); decidir si eso importa para el ejemplo.
- ~~DLQ por consumer~~ Resuelto (ADR 0008): una DLQ por contexto consumidor (`interactions.dlq`, `feed.dlq`, `notifications.dlq`).
- Retención del inbox: decisión nuestra, sin fuente (nota 03).
- Cómo ve el consumer el conteo de entregas (discrepancia abierta, nota 07).
- Política exacta de tope y espera: medirla en experimentos (nota 04).
- Comportamiento de `onPartitionsAssigned` incremental con nuestros listeners.

## Respaldo
Notas 02, 03, 04, 06, 07 y 09; nota 16 para la aclaración del ADR 0009.
