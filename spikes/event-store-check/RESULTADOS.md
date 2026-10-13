# Resultados del spike S4: event store de `Comment` en PostgreSQL

> **Fecha de la corrida:** 2026-10-12 · **Comando:** `./gradlew clean test` (en `spikes/event-store-check/`) · **Resultado:** 18 pruebas, 18 en verde, 34 s con las imágenes ya descargadas · **Alcance:** hipótesis del ADR 0009 (puntos 15, 16, 18 y 19), experimentos 6 y 7 de `garantias.md`, escenarios ES-01 a ES-10 y la monotonía de `Uuid.generateV7()` (ID-03). **Sin tocar** `compose.yaml` ni los demás módulos: PostgreSQL y Kafka los levanta Testcontainers en puertos aleatorios.

## Versiones reales

| Pieza | Versión | Cómo se verificó |
|---|---|---|
| PostgreSQL | imagen `postgres:18.6` | tag fijo en `TestInfrastructure.kt`; Testcontainers lo levantó |
| Driver JDBC | `org.postgresql:postgresql:42.7.14` | última en Maven Central al 2026-10-12 (`maven-metadata.xml`) |
| Kafka (broker y cliente) | `apache/kafka:4.3.1`, `kafka-clients` 4.3.1 | log del cliente: `Kafka version: 4.3.1` |
| Kotlin | 2.4.21, `-opt-in=kotlin.uuid.ExperimentalUuidApi` | `build.gradle.kts` |
| JUnit | 5.14.4 (última 5.x; la 6.1.3 existe y no se usó) | Maven Central |
| Testcontainers | 2.0.5 (`testcontainers-postgresql`, `testcontainers-kafka`) | Maven Central |
| JDK / Gradle | Corretto 21.0.10 / Gradle 9.8.1 (wrapper copiado de `spikes/stack-check`) | `gradle-wrapper.properties` |

**Persistencia:** JDBC plano, sin Spring Data ni ORM (la capa sigue sin decidir).

## Resultados por pregunta

| # | Pregunta | Veredicto | Evidencia |
|---|---|---|---|
| **ES-01** | Stream sin eventos: ¿no existe, y `EditComment` se rechaza sin escribir? | **Pasó** | `rehydrate` devuelve `null`; 0 filas en `comment_events` y `comment_view` |
| **ES-02** | Primer append con "stream inexistente": ¿guarda la versión 1 y repetirlo falla? | **Pasó** | Versión 1; el segundo falla con `duplicate key value violates unique constraint "comment_events_stream_id_version_key"` |
| **ES-03** | Publicar: ¿un evento y la vista actualizados? | **Pasó** | 1 fila `CommentPosted` versión 1; `comment_view` = `ACTIVE`, versión 1 |
| **ES-03 d** | ¿La proyección se revierte si el append falla (misma transacción)? | **Pasó** | Falla inyectada justo antes del `COMMIT`: 0 eventos y 0 filas de vista |
| **ES-04** | Rehidratar un stream de un evento | **Pasó** | `CommentState(commentId, videoId, authorId, "Muy bueno", deleted=false, version=1)` |
| **ES-05** | Historia completa: post, 2 ediciones y delete | **Pasó** | Versiones 1 a 4 sin huecos; textos anteriores en el stream; estado final borrado |
| **ES-06** | Dos ediciones concurrentes, ambas leyeron la versión 1: ¿un ganador y un `VersionConflict`? | **Pasó** | 20 repeticiones, en las 20 exactamente 1 ganador y 1 `VersionConflict`; el perdedor releyó y agregó la versión 3 (versiones 1, 2, 3) |
| **ES-06 b** | Ocho escritores sobre la misma versión | **Pasó** | 1 ganador, 7 `VersionConflict` |
| **ES-07 / G8** | Reproyección: vaciar `comment_view` y reconstruirla por `global_seq` | **Pasó** | 30 comentarios con ediciones, borrados y cruces: la vista queda idéntica; `published_at` sin cambios |
| **ES-09** | Editar un comentario borrado | **Pasó** | Rechazo de dominio (`CommentRejected`) sin escribir |
| **ES-10** | Insert manual de una versión repetida | **Pasó** | `sqlState=23505`, `comment_events_stream_id_version_key` |
| **ID check** | ¿El `CHECK` acepta UUIDv7 y rechaza lo demás? | **Pasó** | UUIDv7 aceptado; rechazados con `23514`: UUID v4 en `stream_id` y en `event_id`, prefijo `vid_` en `stream_id`, prefijo `cmt_` en `event_id`, UUID sin prefijo y `c-1` |
| **ID-03** | ¿`Uuid.generateV7()` respeta el orden dentro del mismo milisegundo? | **Pasó en las condiciones medidas** | 200.000 ids en un bucle cerrado, un hilo: 199.881 pares consecutivos en el mismo milisegundo, **0 fuera de orden**, 0 retrocesos de timestamp, 0 repetidos |
| **CommentEventPublisher** | ¿Lee por `global_seq`, publica con key = `stream_id` y marca `published_at` tras el envío? | **Pasó** | 5 eventos de 2 streams: `subject` = key; versiones de cada key en orden del log; una key vive en una sola partición; `published_at` queda en 0 nulos |
| **ES-08 / exp. 6** | Caída entre publicar y marcar | **Pasó** | El primer `publishPendingEvents` envió y murió (`SimulatedCrash`): `published_at` siguió nulo y el broker tenía 1 registro. El reinicio lo reenvió: **2 registros con el mismo `event_id`** y la misma key. El consumer con inbox (`source`+`id`) aplicó **1** efecto y descartó 1 |
| **Exp. 7** | Dos instancias del publisher sin bloqueo (`PLAIN`) | **Duplica todo** | A leyó las 50 filas y se demoró; B las leyó, envió y marcó; A las envió otra vez: **100 registros, 50 `event_id` distintos** |
| **Exp. 7 b** | Dos instancias con `FOR UPDATE SKIP LOCKED` | **Sin duplicados, pero rompe el orden por key** | A (lote 25) bloqueó las filas 1 a 25 (incluye la versión 1 de X) y se demoró; B tomó las 26 a 31 (incluye la versión 2 de X). **31 registros, 31 distintos, y las versiones de X llegaron al log en el orden `[2, 1]`** |
| **Exp. 7 c** | `pg_try_advisory_lock` entre dos conexiones | **Pasó** | La segunda conexión no obtiene el lock (`false`) mientras la primera lo tiene; lo obtiene al liberarse |

### Los resultados literales
```
ES-06 (error del perdedor):  ERROR: duplicate key value violates unique constraint "comment_events_stream_id_version_key"
ES-10:                       sqlState=23505 (mismo mensaje)
ID check:                    sqlState=23514 ... violates check constraint "comment_events_stream_id_check" / "comment_events_event_id_check"
ID-03:                       ids generados: 200000 · distintos: 200000 · pares en el mismo ms: 199881 · fuera de orden: 0 · retrocesos: 0
Dos instancias PLAIN:        B envió 50, A envió 50, registros en el topic: 100, event_id distintos: 50
Dos instancias SKIP LOCKED:  A envió 25, B envió 6, registros: 31, distintos: 31 · orden de versiones de X: [2, 1]
```
Los eventos publicados en las pruebas (5 registros: `CommentPosted`, `CommentEdited` y `CommentDeleted`, algunos duplicados) se volcaron a `build/evidence/captured-events.jsonl` y se validaron **fuera de Gradle** con `jsonschema` (Draft 7, el venv del scratchpad) contra `comment-posted.json`, `comment-edited.json` y `comment-deleted.json`: **5 de 5 válidos**.

## Qué dicen sobre las hipótesis del ADR 0009

| Hipótesis (ADR 0009 / nota 16) | Estado tras el spike |
|---|---|
| `UNIQUE (stream_id, version)` alcanza como control de concurrencia optimista (ES-06, ES-10) | **Confirmada** con threads reales contra PostgreSQL 18.6: un ganador, el resto recibe `23505` |
| El polling publisher puede leer del event store con `published_at IS NULL` ordenado por `global_seq` | **Confirmada** |
| La proyección en la misma transacción mantiene la vista consistente y se revierte junto con el append | **Confirmada** |
| La reproyección es idéntica y no toca `published_at` (G8) | **Confirmada** (30 streams) |
| Caída entre append y publicación: el evento sale y se aplica una sola vez gracias al inbox | **Confirmada**: sale dos veces, el efecto ocurre una vez |
| Una sola instancia publicadora (ADR 0004, punto de orden) | **Respaldada**: con dos instancias hay duplicados (`PLAIN`) o pérdida del orden por key (`SKIP LOCKED`) |
| Los `CHECK` por expresión regular del DDL de la nota 17 | **Confirmados en PostgreSQL**; hasta hoy solo se habían probado en Python |

## Hallazgos que precisan lo documentado

1. **El DDL de la nota 17 y del ADR 0009 funciona tal cual** en PostgreSQL 18.6. Agregué un índice parcial `comment_events_unpublished ON (global_seq) WHERE published_at IS NULL` (decisión mía, no está en el ADR) para el polling.
2. **`SKIP LOCKED` no sirve como forma de escalar el publisher**: evita el doble envío pero un lote bloqueado por una instancia lenta deja pasar eventos posteriores de la misma key. Es un orden roto *por key*, justo lo que garantiza la key de Kafka. La regla "una sola instancia publicadora" del ADR 0004 queda justificada con evidencia.
3. **La demora de la instancia A se simuló con una pausa** (`afterSelect`): es una ventana de carrera determinista, no una carrera natural. La conclusión (sin bloqueo, la misma fila se lee dos veces) no depende de la suerte, pero no medí con qué frecuencia ocurriría sin la pausa.
4. **El `ConsumerRecord` y el inbox se simularon con tablas de PostgreSQL** (`inbox`, `notification_effect`), en la misma base; no es el consumer real de Notifications. Alcanza para probar la regla `source`+`id` en una transacción.
5. **El nombre del constraint lo pone PostgreSQL por defecto** (`comment_events_stream_id_version_key`). El código distingue un conflicto de versión por ese nombre; conviene darle nombre explícito en el DDL definitivo o tratar cualquier `23505` sobre `comment_events` como candidato a conflicto y verificar la versión.
6. **Hubo un error de mis pruebas, no del diseño:** la primera corrida dio 1 falla (`NoSuchElementException: List is empty`) en ES-08. La causa fue un helper de consulta que usaba `generateSequence` y se cortaba con un valor `null` (`published_at` nulo). Se corrigió el helper con un bucle y la segunda corrida dio 18 de 18.
7. **`kotlin.uuid.Uuid.generateV7()` requiere `@OptIn(ExperimentalUuidApi)`** (sigue experimental en 2.4.21). `Uuid.random()` genera la versión 4 que usé para los casos negativos.

## Qué no se probó
- **Varios procesos o máquinas escribiendo** el mismo stream: los escritores concurrentes fueron hilos de una sola JVM contra un solo PostgreSQL.
- **Monotonía de `generateV7()` con varios hilos o procesos**, y con el reloj atrasándose: solo un hilo, un bucle cerrado. La documentación de Kotlin no garantiza el orden dentro del milisegundo; lo medido es una observación, no una garantía.
- **Rehidratación de streams largos** y rendimiento: ningún stream pasó de 4 versiones y nada se midió.
- **Upcasting** (`.v2`): no hay ningún evento con versión distinta de la actual.
- **Caída real del proceso** (matar la JVM): la caída es una excepción lanzada en el publisher, no un `kill -9`.
- **Desorden del `global_seq` frente al orden de commit** (una transacción con un `global_seq` menor que confirma después de una con uno mayor): el diseño por marca `published_at` evita depender de un cursor, pero no lo probé; es razonamiento, no medición.
- **Producer con reintentos del lado de Kafka** (`enable.idempotence` ante timeouts) y el `acks` bajo fallos del broker.
- **Un `GET` de lectura propia justo tras el comando** y la API síncrona: no hay capa HTTP en este spike.

## Consecuencias para los ADR y las notas (sin editarlos)
- **ADR 0009:** la hipótesis marcada "sin fuente directa" en los puntos 15, 16, 18 y 19 pasa a **verificada experimentalmente** en un nodo; agregar la referencia a este documento y mantener "varias instancias del publicador" como regla (una sola).
- **ADR 0004:** agregar la excepción de `Comment` como probada, y fijar que **la instancia publicadora es única** (advisory lock opcional para la elección) con la evidencia de `PLAIN` y `SKIP LOCKED`. No hay que borrar `published_at` en `comment_events` (append-only).
- **Nota 16:** quitar de *No verificado* "el DDL no se ejecutó" y la restricción única; agregar el nombre por defecto del constraint y el hallazgo 5.
- **`garantias.md`:** experimentos 6 y 7 pasan a hechos; queda abierto "caída real del proceso" y "varias JVM".
- **ID-03 (nota 17):** la monotonía quedó medida en un solo hilo; el resto sigue siendo experimento.

## Cómo repetirlo
```
cd spikes/event-store-check
./gradlew clean test
```
Hace falta Docker y un JDK 21. La primera corrida descarga las dependencias y las imágenes `postgres:18.6` y `apache/kafka:4.3.1`. Las evidencias literales quedan en `build/evidence/`.


## Anexo 2026-10-14: JDK 25 LTS

- **Cambio:** `jvmToolchain` pasa de 21 a **25** (Corretto 25.0.1+8, `~/.sdkman/candidates/java/25.0.1.0+8-amzn`, usado vía `JAVA_HOME` sin cambiar el default de sdkman); `.sdkmanrc` en la raíz con `java=25.0.1.0+8-amzn`. Pines sin tocar (Boot 4.1.1, Spring Kafka 4.1.1, kafka-clients 4.3.1, broker 4.3.1, Kotlin 2.4.21, Pact 4.7.5, Testcontainers 2.0.5, Gradle 9.8.1).
- **Resultado:** `./gradlew clean test` con JDK 25: **18 pruebas, 18 en verde** (2 min 56 s). Antes (JDK 21): 18 en verde.
- **Avisos:** ningún warning del compilador de Kotlin (`w:`) con target 25. Avisos de la JVM: `A Java agent has been loaded dynamically` (ByteBuddy/Mockito, "will be disallowed by default in a future release") y `Sharing is only supported for boot loader classes…`.
- **Gradle 9.8.1** detectó el toolchain 25 y corrió con Launcher JVM `25.0.1 (Amazon.com Inc. 25.0.1+8-LTS)`.

## Anexo 2026-10-14: prueba exploratoria con Kafka 4.4.0

- **Qué se probó:** la parte de Kafka de S4 (publisher, caída entre publicar y marcar, dos instancias) con `kafka-clients` **4.4.0** y `apache/kafka:4.4.0-rc4`; PostgreSQL 18.6 sin cambios. Comando: `./gradlew clean test -Pkafka=4.4.0 -PkafkaImage=apache/kafka:4.4.0-rc4` (JDK 25).
- **Resultado:** **18 de 18 en verde** (1 min 55 s). Sin diferencias de comportamiento en el publisher ni en el inbox.
- **Estado de la release:** ver el anexo de `stack-check/RESULTADOS.md` (sin anuncio oficial de GA encontrado).

## Anexo 2026-10-16: renombrado semántico

Sin cambios de comportamiento; los 18 tests siguen en verde. Nombres anteriores → actuales: `CommentStore` → `CommentEventStore` (`post/edit/delete/append/rehydrate/reproject` → `postComment/editComment/deleteComment/appendEvent/rehydrateComment/rebuildCommentView`), `Projection` → `CommentViewProjection`, `Publisher` → `CommentEventPublisher` (`runOnce` → `publishPendingEvents`), `LockMode.PLAIN` → `RowLocking.NONE`, `Schema` → `DatabaseSchema`, `Ids` → `PrefixedIds`, `Pg`/`Kafka` (tests) → `PostgresFixture`/`KafkaFixture`. Las secciones de arriba conservan los nombres de la fecha en que se ejecutó cada prueba.
