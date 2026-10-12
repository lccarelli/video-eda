# Garantías no funcionales

> **Estado:** borrador para revisión · **Última actualización:** 2026-10-12

Cada garantía tiene un mecanismo y una prueba. Sin prueba no es una garantía, es una intención.

## Garantías de mensajería

| # | Garantía | Mecanismo | Prueba | ADR |
|---|---|---|---|---|
| G1 | Orden por agregado | Key = aggregate id, producer idempotente, sin partitioner propio | Orden por key contra broker real, incluido follow/unfollow del mismo par en `feed.follow` (NOT-14) | 0003 |
| G2 | Sin pérdida entre estado y evento | Outbox transaccional; para `Comment`, el event store ocupa su lugar (nota 16) | Matar el servicio tras el commit, para `Video` y `Follow` (FEED-14). **Parcial:** la escritura atómica estado + outbox y el publisher de `Video` están probados contra PostgreSQL y Kafka (2026-10-17); falta la caída real con `kill -9` | 0004 |
| G3 | Entrega at-least-once | Polling publisher del outbox o del event store, offsets confirmados tras procesar | Reiniciar consumer a mitad de lote; matar el servicio entre el append y la publicación (ES-08) | 0004, 0005 |
| G4 | Efecto único por evento | Inbox por `source` + `id` en la misma transacción | Enviar el mismo `id` dos veces, también un `CreatorFollowed` (NOT-15) | 0005 |
| G5 | Nada se pierde en silencio | DLQ propia, alerta, archivados visibles | Agotar intentos y verificar la DLQ | 0005 |
| G6 | Contratos verificados | Pact, validación de schema y `can-i-deploy` en CI | Romper un campo y ver el build fallar | 0002, 0006 |
| G7 | La key de cada topic no cambia sin aviso | Test propio; Pact y schema **no** la cubren (nota 10) | Cambiar la key y ver el test fallar | 0003 |
| G8 | Un read model es función de los eventos: se puede vaciar y reconstruir idéntico | Proyector idempotente (inbox + guard por `sequence`); fuente completa (`retention.ms=-1` en los topics reproyectables o el event store de `Comment`) | Vaciar el read model, reproyectar y comparar (QRY-04, ES-07) | 0009 *(propuesto)* |
| G9 | El orden por agregado sobrevive a una falla y a un reproceso | Guard estricto de `sequence`, DLQ con key original, redrive en orden por el mismo handler | Aparte un evento, siga el tráfico y verifique que el siguiente de la misma key no se aplica hasta el redrive (RES-01, RES-02) | 0011 *(aceptado; S5 lo verificó con clientes planos, sin Spring)* |

## Qué NO se garantiza
- **Exactly-once end to end:** solo existe dentro de Kafka; el resto es at-least-once más idempotencia (nota 03).
- **Orden entre topics:** no existe; se define una política por caso (ADR 0003).
- **Orden en share groups:** no hay; los consumers son idempotentes y tolerantes.
- **Lectura inmediata de la propia escritura entre contextos:** la consistencia es eventual (nota 14). Solo la vista de comentarios de Interactions se actualiza en la misma transacción que el append.
- **Borrado físico de comentarios:** `CommentDeleted` es borrado lógico; el texto permanece en el historial del event store y en los mensajes ya publicados.
- **Tiempo de entrega:** el relay y los reintentos agregan latencia sin cota fija.

## Objetivos de servicio (a definir con mediciones)

| Indicador | Objetivo propuesto | Cómo se mide |
|---|---|---|
| Latencia de `VideoPublished` hasta la entrada en el feed | A definir | Diferencia de `time` entre eventos |
| Edad del evento más viejo sin publicar en el outbox | A definir | Consulta sobre la tabla |
| Lag de consumer por grupo | A definir | Métricas del consumer |
| Mensajes en DLQ sin atender | Cero a las 24 h *(propuesta)* | Alerta |

No hay objetivos numéricos aún: faltan mediciones en un broker real. No se inventan números sin datos.

## Experimentos pendientes
1. Orden por key bajo rebalances y cambio de particiones (nota 02, vacío).
2. ~~Comportamiento del polling publisher ante caída del servicio y reinicio~~ **Hecho (spike S4, un nodo):** el mismo `event_id` sale dos veces y el inbox lo absorbe; dos instancias duplican o rompen el orden salvo con `pg_try_advisory_lock`. Sin probar `kill -9` real.
3. ~~Lock expirado y RENEW en el share group del worker~~ **Hecho (spike S2, 2026-10-12):** el lock vencido re-entrega; `RENEW` funciona con el cliente directo y no con `renew()` dentro del listener de Spring. Quedan sin probar caída abrupta y rebalances.
4. ~~Discrepancia del conteo de entregas~~ **Cerrada (spike S2):** `deliveryCount()` se expone.
5. **Reproyección (G8)** — *parcial: `comment_view` desde `comment_events` verificado en S4; falta `feed_entry` desde `videos.video` y medir el disco:* vaciar y reconstruir `feed_entry` desde `videos.video` con `retention.ms=-1` y `comment_view` desde `comment_events`; medir el costo de disco de la retención infinita (QRY-04, ES-07).
6. ~~Caída entre el append y la publicación~~ **Hecho (spike S4, un nodo):** el mismo `event_id` se publica dos veces y el inbox aplica el efecto una; el publisher lee por marca `published_at`. Sin probar `kill -9` real ni varios procesos.
7. ~~Event store sin fuente directa~~ **Verificado (spike S4, un nodo):** `UNIQUE (stream_id, version)` da un ganador por versión y el publisher lee del store.
