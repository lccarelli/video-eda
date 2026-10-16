# Resultados del spike S5: DLQ secuenciada, redrive en orden y dependencia caída

> **Fecha de la corrida:** 2026-10-16 · **Comando:** `./gradlew test --rerun-tasks` (en `spikes/resilience-check/`, con `sdk env` en la raíz para JDK 25) · **Resultado:** 12 pruebas, 12 en verde, **3 corridas completas seguidas** (las dos primeras con Gradle lanzado por JDK 21 y compilando/probando con el toolchain 25; la tercera con Gradle lanzado por JDK 25), ~80–90 s con las imágenes ya descargadas · **Alcance:** hipótesis del ADR 0011 (propuesto), escenarios RES-01 a RES-05 y RES-07 de `specs/06-scenarios/11-resiliencia.md`, garantía G9. PostgreSQL y Kafka los levanta Testcontainers; no se tocó `compose.yaml`.

## Versiones reales

| Pieza | Versión | Cómo se verificó |
|---|---|---|
| Kafka (broker y cliente) | `apache/kafka:4.3.1`, `kafka-clients` 4.3.1 | log del cliente: `Kafka version: 4.3.1` |
| PostgreSQL | `postgres:18.6`, driver `42.7.14` | `TestInfrastructure.kt`, `build.gradle.kts` |
| Kotlin / JDK | 2.4.21 / toolchain 25 (Corretto 25.0.1) | `build.gradle.kts`, `./gradlew --version` |
| Testcontainers / JUnit | 2.0.5 / 5.14.4 | igual que `event-store-check` |

**Qué es:** clientes Kafka y JDBC planos, **sin Spring**. El consumer del spike implementa a mano el reintento acotado, la DLQ y la pausa. No prueba `DefaultErrorHandler` ni `@Retryable` de Spring.

## Resultados por pregunta

| # | Pregunta | Veredicto | Evidencia |
|---|---|---|---|
| **RES-01** | Si `CommentPosted` (seq 1) falla y llega `CommentEdited` (seq 2) de la misma key, ¿el segundo se aparta solo y las demás keys siguen? | **Pasó** | Primero a la DLQ por `RETRIES_EXHAUSTED` (3 intentos), el segundo por `SEQUENCE_GAP` (1 intento, sin reintento). Misma key y misma partición, `dlt-original-offset` creciente. El comentario sano quedó con su edición. Los 2 eventos apartados **no** quedaron en el inbox (solo 2 filas: las del sano). Lag del grupo = 0 |
| **RES-02** | Corregida la causa, ¿el redrive reaplica en orden y el texto final es el de la edición? | **Pasó** | `RedriveSummary(applied=2, discarded=0, stillPending=0)`; copia = "Gracias, quedó riquísima"; `last_sequence = 2` |
| **RES-03** | ¿Repetir el redrive repite efectos? Y si la causa **no** se corrigió, ¿se pierde algo? | **Pasó** | Segundo redrive: `applied=0, discarded=2`. Sin corregir: `stillPending=2` y sin cambios en la copia |
| **Caso que falla A** | Con el guard de la nota 03 (solo descarta `seq <= última`), ¿qué pasa? | **Reproducido** | El `CommentEdited` se aplicó como `UPDATE` de 0 filas, la posición quedó en 2, y en el redrive el `CommentPosted` (seq 1) se descartó **por viejo**: el comentario **no existe** (`copia=null`) |
| **Caso que falla B** | Sin ningún guard | **Reproducido** | El redrive inserta el comentario con el texto viejo "Gracias"; la edición se perdió sin error |
| **RES-04** | ¿Un evento sin `sequence` va directo a la DLQ sin reintentos y no frena al siguiente? | **Pasó** | `PERMANENT_INVALID_EVENT`, `dlt-attempts=1`, key original; el evento válido siguiente se aplicó |
| **RES-05** | Proveedor caído 5 s con `max.poll.interval.ms=3000`: pausando las particiones y llamando a `poll()`, ¿se queda en el grupo y no manda nada a la DLQ? | **Pasó** | `applied=1, deadLettered=0`, una sola generación del grupo (`[1]`), DLQ con profundidad 0 |
| **RES-05 b** | Contrafactual: esperando **sin** `poll()`, ¿qué pasa? | **Confirmado** | La primera corrida terminó con `CommitFailedException` (el miembro fue expulsado); la segunda corrida (generación 3) recibió de nuevo el registro y lo **descartó por el inbox**: 1 fila en `comment_copy` |
| **RES-07** | 50 comentarios, 10 con video desconocido, ¿consistentes al final? | **Pasó** | 80 eventos aplicados y 20 apartados; en la DLQ cada key tenía `sequence` `[1, 2]` en orden; tras corregir los 10 videos, el redrive aplicó 20 y los 50 comentarios quedaron con "editado N". Lag = 0 |
| **Caída antes del commit** | Si el consumer publica en la DLQ y muere antes de confirmar el offset, ¿qué queda? | **Pasó** | 4 entradas en la DLQ con solo 2 `event_id` distintos; el redrive aplicó 2 y descartó 2 duplicados |
| **Inundación** | 200 eventos que fallan todos: ¿se pierde alguno? ¿queda atrasado el consumer? | **Pasó** | Profundidad de la DLQ = 200 (suma de offsets finales), lag = 0, conjunto de `event_id` de la DLQ = conjunto producido |

## Hallazgos que cambian o precisan lo documentado

1. **El caso que falla de la nota 19 estaba incompleto.** Con el guard de la nota 03, el resultado **no** es "el texto viejo": es que **el comentario nunca existe** (el `CommentPosted` se descarta como viejo porque la edición ya fijó la posición en 2). Sin guard sí aparece el texto viejo. Ambos defectos son silenciosos. Nota 19 corregida.
2. **Un defecto propio de la herramienta de redrive, hallado por RES-07:** `poll()` devuelve registros de **todas** las particiones asignadas; procesar solo las de la partición actual descarta las demás (aplicó 10 de 20). Corregido asignando una partición por vez. Con un solo partición (RES-02) no se veía. Regla para la herramienta de operación.
3. **El guard estricto funciona como la cuarentena que Axon implementa con un cache de secuencias apartadas**, sin estado propio, siempre que `sequence` sea contigua (acá lo construye el test; en `Comment` la da `UNIQUE (stream_id, version)`).
4. **Pausar + `poll()` evita el rebalance; no pausar lo provoca**, con las dos pruebas (RES-05 y RES-05 b). Lo que no se probó es el efecto de pausar *sin* llamar a `poll()`.
5. **La DLQ de Kafka no tiene tope** (a diferencia de la de Axon, que se desborda a 1024 por secuencia): la profundidad se mide con los offsets finales. Habrá que definir la alerta nosotros.

## Lo que este spike NO prueba
- **Spring:** `DefaultErrorHandler`, `DeadLetterPublishingRecoverer`, `@Retryable`, `@ConcurrencyLimit` y `ContainerPausingBackOffHandler` (clientes planos).
- **Concurrencia entre el consumer en vivo y el redrive:** mientras el redrive corre, llega un `N+2` y se aparta detrás; no se probó que el orden se conserve en esa carrera.
- **Marca de "ya resuelto":** el redrive relee **toda** la DLQ cada vez (lo ya aplicado se descarta por el inbox). Falta definir cuándo se purga o archiva la DLQ y quién decide **omitir** un evento aceptando un hueco (equivalente a `doNotEnqueue`/`evict` de Axon): sin esa decisión, un evento perdido para siempre aparta a todos los de su key para siempre.
- **Contigüidad de `sequence` en Video y Follow** (outbox).
- **Rebalances durante el procesamiento, `kill -9`, varios consumers del mismo grupo, el protocolo `group.protocol=consumer`**, rendimiento y tiempos (los de las pruebas no significan nada).
- **Reproyección completa con `--reset-offsets`** (RES-08) y el tope de concurrencia (RES-09).
- **La DLQ en tabla de la base** como variante.
