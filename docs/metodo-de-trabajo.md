# Método de trabajo

Reglas del proyecto. Se leen antes de trabajar acá y se cumplen en cada turno.

## Qué es este proyecto

Ecosistema de microservicios con **arquitectura orientada a eventos sobre Kafka**, **contract testing** y **delivery** (pipeline, release gates), construido **spec-driven** y pensado como aprendizaje profundo y como evidencia demostrable (ordering, idempotencia, contratos, `can-i-deploy`).

- **Dominio:** plataforma de videos. Creators publican, otros miran, puntúan y comentan, y los seguidores ven las publicaciones en su feed.
- **Contextos (un servicio cada uno):** `Videos`, `Interactions` (puntuaciones, comentarios, vistas), `Feed`, `Notifications`. Moderación queda como extensión opcional.
- **No es** un dominio de orders/e-commerce. Los ejemplos son siempre de este dominio.
- **Nombre de trabajo:** `video-eda` (renombrable).

## Método de trabajo

1. **Spec primero, código después.** No se escribe código de servicio sin spec y contrato aprobados.
2. **Estudio dirigido por inputs.** Laura pasa un tema; se investiga en fuentes primarias, se explica y se documenta.
3. Flujo por feature: spec → revisión → contrato → test que falla → implementación → gate en CI.
   - Los tests de cada comportamiento se escriben en orden **ZOMBIES**: cero, uno, muchos, y recién después boundary, interface y exception (`specs/06-scenarios/07-zombies.md`).
4. Antes de tocar código existente o crear estructura nueva, presentar el plan de archivos y esperar confirmación (misma regla que `turnify`).

## Reglas de documentación (obligatorias)

Estas cinco reglas se aplican a **todo** lo que se estudie o decida.

### 1. Cada tema queda asentado en un `.md`
- Todo tema estudiado se documenta en `docs/study/NN-tema.md`, explicado para **cada componente** del proyecto (sección "Aplicación por componente": Videos, Interactions, Feed, Notifications y los transversales que correspondan).
- Se escribe en el **mismo turno** en que se aprende. Nada queda solo en la conversación.
- Una nota por tema; si el tema ya existe, se **actualiza**, no se crea otra.

### 2. Siempre links a documentación de respaldo
- Cada afirmación técnica relevante lleva link a **fuente primaria** (documentación oficial, KIP, especificación, blog de ingeniería de quien lo opera).
- Se fija la **versión** (por ejemplo "Kafka 4.3") y la **fecha de consulta**.
- Cada nota termina con dos secciones: **Fuentes** y **No verificado**. Lo que sea inferencia propia, resumen de blog sin fuente primaria o conocimiento previo se marca explícitamente como tal.
- Si dos fuentes se contradicen, se documenta la discrepancia, no se elige en silencio.

### 3. Siempre ejemplos de nuestro dominio
- Cada concepto se ilustra con eventos, topics y escenarios reales del proyecto: `VideoPublished`, `CommentPosted`, `CommentDeleted`, `VideoRated`, `CreatorFollowed`, `videos.video`, key `videoId`, etc.
- Se incluyen ejemplos concretos (payload CloudEvents, fragmento AsyncAPI, escenario Given/When/Then) y, cuando aplique, el caso que falla antes del que funciona.

### 4. Siempre diagramas
- Cada nota lleva al menos un diagrama, elegido por la pregunta que responde (tabla abajo). Se aclara en una línea **por qué** se eligió ese tipo.
- Formato: **Mermaid** dentro del `.md` (renderiza en GitHub y Obsidian).
- Un diagrama responde una sola pregunta. Si hay varias, varios diagramas.

### 5. Siempre actualizar
- Si algo cambia (una versión, una decisión, una corrección), se actualiza la nota afectada **y** lo que dependa de ella: otras notas, `docs/study/README.md`, ADRs y specs.
- Cada nota tiene **Historial** con fecha y qué cambió. Lo obsoleto se marca como obsoleto con la razón, no se borra en silencio.
- **Cierre de turno:** antes de terminar un turno en que se aprendió o decidió algo, verificar la checklist:
  - [ ] ¿La nota del tema existe y está actualizada?
  - [ ] ¿Tiene fuentes con versión y la sección "No verificado"?
  - [ ] ¿Tiene ejemplos del dominio y al menos un diagrama?
  - [ ] ¿Se actualizó `docs/study/README.md`?
  - [ ] ¿Algún ADR o spec cambió por esto?

## Qué diagrama UML usar

| Pregunta que respondés | UML | Mermaid |
|---|---|---|
| ¿Cómo viajan los mensajes en el tiempo? (publicar → consumir, retry, saga) | **Secuencia** | `sequenceDiagram` |
| ¿Qué estados atraviesa algo? (ciclo de un video, un registro de share group, una saga) | **Máquina de estados** | `stateDiagram-v2` |
| ¿Qué servicios y topics hay, y quién publica/consume? | **Componentes** | `flowchart` con `subgraph` (Mermaid no tiene componente nativo) |
| ¿Cómo se estructura un evento o schema y cómo se relacionan los tipos? | **Clases** | `classDiagram` |
| ¿Qué decisión se toma y por qué rama? (¿reintento bloqueante?, ¿duplicado?) | **Actividad** | `flowchart` |
| ¿Cómo se despliega? (brokers, particiones, réplicas, entornos) | **Despliegue** | `flowchart` con `subgraph` |
| ¿Qué tablas hay? (outbox, inbox, read models) | **ER** (no es UML puro) | `erDiagram` |
| ¿Quién usa qué? (alcance funcional) | **Casos de uso** | `flowchart` |

## Plantilla de notas

La plantilla está en `docs/study/_template.md`. Secciones fijas:
Resumen · Por qué importa · Cómo funciona (con diagrama) · Aplicación por componente · Ejemplos del dominio · Alternativas y trade-offs · Reglas para el spec · Fuentes · No verificado · Historial.

## Convenciones de investigación

- Preferir **documentación oficial versionada** (por ejemplo `kafka.apache.org/43/...`) antes que blogs. Los blogs sirven para lecciones operativas y se marcan como tales.
- Separar siempre **lo leído** de **lo inferido** de **lo recordado sin verificar**.
- Si una página se leyó parcialmente, decirlo (por ejemplo "leídos 100.000 de 225.000 caracteres").
- Verificar nombres de configuración contra la página de configs de la versión: los nombres cambian entre versiones y entre niveles (group, broker, consumer).
- Cuando algo no está documentado, la respuesta correcta es "no encontré", y se propone cómo probarlo en un experimento.

## Estructura del repositorio (objetivo)

```
video-eda/
  docs/metodo-de-trabajo.md
  docs/study/            # notas de estudio (índice en README.md)
  specs/
    00-vision.md
    01-domain/           # event storming, bounded contexts, glosario
    02-adr/              # decisiones de arquitectura
    03-events/           # AsyncAPI + schemas por evento
    04-apis/             # OpenAPI de los endpoints síncronos
    05-contracts/        # contratos consumer-driven
    06-scenarios/        # criterios de aceptación Given/When/Then
    07-nfr/              # SLOs, garantías de ordering e idempotencia
  services/              # videos, interactions, feed, notifications
  compose.yaml           # el ecosistema completo con `docker compose up --build` (ADR 0007, enmienda 2026-10-12)
  deploy/                # create-topics.sh e init-databases.sql que usa compose.yaml; workflows en .github/workflows
```

## Decisiones vigentes (hasta que existan ADRs)

- **Envelope:** CloudEvents · **Contrato:** AsyncAPI 3.1.0 (3.0.0 estudiado; 3.1.0 validado con la CLI oficial) · **Payload:** JSON Schema, sin Avro ni Schema Registry (ADR 0008). JSON:API no se usa para eventos.
- **Kafka:** 4.x, fijando **≥ 4.2.1** si se usan share groups. Producer idempotente por defecto; prohibido configurar el partitioner del producer.
- **Consumer groups** con `group.protocol=consumer` para flujos con orden por key. **Share group** solo para el worker de procesamiento de video (ADR 0008), con DLQ propia, `share.auto.offset.reset=earliest` e idempotencia. Notifications usa un consumer group.
- **Publicación:** Transactional Outbox + **polling publisher** dentro del servicio (ADR 0004, ADR 0008), con `aggregate_id` como key; para `Comment` el event store ocupa el lugar del outbox (ADR 0009); `Video` y `Follow` usan outbox. Debezium queda como camino de crecimiento.
- **DLQ:** una por contexto consumidor (`interactions.dlq`, `feed.dlq`, `notifications.dlq`) más `videos.processing.dlq` del worker.
- **Sagas:** la publicación de video es un process manager orquestado dentro de Videos, con pivote en `VideoPublished`; Feed y Notifications reaccionan por coreografía sin compensación (nota 08). La orquestación con pocos participantes es **hipótesis a validar**: AWS la recomienda para "many participants".
- **Consumo:** at-least-once con inbox por `eventId` en la misma transacción que el read model.
- **Clientes:** el proyecto Apache Kafka mantiene solo el cliente Java (y Streams en Java/Scala); Spring Kafka 4.1.x (cliente 4.2.x) soporta KIP-848 desde 4.0 y share groups en producción desde 4.1, sin DLQ ni message converters para share consumers (notas 07 y 09). **Verificado en el spike S1/S2 (2026-10-12, nota 18):** arrancan los tres tipos de consumer con Spring Kafka 4.1.1 y `kafka-clients` 4.3.1; `ShareAcknowledgment.renew()` no mantiene el lock dentro del listener (el worker usa el cliente directo para RENEW) y `deliveryCount()` sí se expone.
- **Stack (ADR 0001, aceptado 2026-10-12):** Kotlin 2.4.21, Spring Boot 4.1.1, Spring Kafka 4.1.1 con `kafka-clients` sobrescrito a 4.3.x, broker Kafka 4.3.1 (KRaft), Gradle Kotlin DSL, Testcontainers. Fuera de la matriz oficial de Spring: se cubre con test de arranque.
- **Topics y particiones (nota 13):** un topic por agregado; 3 particiones por topic de dominio como elección didáctica (sin carga que medir); configuración por defecto salvo la retención de los topics reproyectables; el video nunca viaja por Kafka.
- **Topics (ADR 0003):** un topic por agregado, formato `<contexto>.<agregado>`, sin versión en el nombre (decisión de Laura, 2026-10-12). La versión vive en el `type` del CloudEvent, **sin sufijo** hasta el primer cambio incompatible (que agrega `.v2`); un cambio incompatible es un `type` nuevo en el mismo topic (nota 12). Varios consumidores por topic: un consumer group por contexto.
- **Repositorio:** público en GitHub (decisión de Laura, 2026-10-12). Es un proyecto de ejemplo, pero con las prácticas de seguridad y calidad de un repositorio público desde el inicio (**ADR 0012**, 2026-10-17, que enmienda la regla anterior de "seguridad fuera de alcance"). Licencia: **MIT**. Repo git propio (`video-eda/`).
- **Plug and play (ADR 0007, enmienda 2026-10-12; verificado en la nota 18):** `docker compose up --build` desde la raíz, solo Docker como requisito, UI de eventos (Kafbat UI) incluida en el arranque por defecto, servicios compilados dentro de Docker (Dockerfile multi-etapa). Fase 0 ejecutada: Kafka 4.3.1, 8 topics, PostgreSQL 18.6, Pact Broker 3.0.0. **Videos corre en Docker** (API, outbox, worker con share group; verificado el 2026-10-17); Interactions, Feed y Notifications aún no existen.
- **Infraestructura (ADR 0007, aceptado 2026-10-12):** Docker Compose (un broker Kafka 4.3.1, servicios, PostgreSQL y Pact Broker) corrido por GitHub Actions en cada pull request. Kubernetes, Strimzi, Argo CD y Terraform quedan como camino de crecimiento (nota 11). Nada desplegado todavía.
- **Contract testing (ADR 0006, aceptado 2026-10-12):** Pact consumer-driven para mensajes + `can-i-deploy` + validación de schema en CI (sin registry). Pact no cubre key, headers ni efectos: eso se prueba aparte (nota 10).
- **DDD táctico + CQRS + event sourcing (notas 14, 15 y 16; decisión de Laura, 2026-10-12; ADR 0009 **aceptado 2026-10-12**):** la inclusión es **didáctica** y amplía el alcance del ADR 0008, no responde a una necesidad del dominio. CQRS en nivel 1 dentro de cada servicio (modelos separados, misma base, sin bus de comandos) y nivel 2 entre contextos. Agregados con id = key del topic. Event sourcing **solo en el agregado `Comment`** (Interactions), con el event store en la base del servicio (no en Kafka); el resto sigue con estado; `Video` y `Follow` usan outbox porque son los otros agregados que publican. `Follow` es el segundo candidato natural a event sourcing y no se aplica por alcance, salvo pedido de Laura. Sin snapshots. Hipótesis sin fuente directa: que el polling publisher lea del event store y la restricción única `(stream_id, version)`.
- **Regla de publicación (aprobada por Laura, 2026-10-12):** un evento se publica en Kafka solo si otro contexto lo consume. Topics resultantes: `videos.video`, `interactions.comment`, `feed.follow` y `videos.processing` (más las DLQ). `feed.follow` existe porque Notifications consume los follows (opción a, Laura, 2026-10-12); Notifications mantiene su propia lista de seguidores. Puntuaciones, vistas y entradas del feed son estado local sin topic.
- **Retención (aprobada, 2026-10-12):** `retention.ms=-1` en `videos.video` e `interactions.comment` para poder reconstruir read models (en `feed.follow` se propuso lo mismo, **a confirmar por Laura**); la retención por defecto de Kafka 4.3 es de 7 días (nota 14). Proyección de `Comment` en la misma transacción que el append; `CommentDeleted` es borrado lógico y el texto queda en el historial.
- **Identificadores (nota 17, aprobado por Laura el 2026-10-12):** `<prefijo>_<UUIDv7>` (`usr_`, `vid_`, `cmt_`, `ntf_`, `evt_`, `cor_`), generado por el servicio dueño antes del INSERT, guardado como texto con `CHECK` de prefijo y versión 7 (binario vs texto: decisión nuestra, sin medir). El id del stream es el id del agregado; no hay id de "commit". Ids compuestos `{followerId}:{creatorId}` y `{videoId}:{raterId}`. Los escenarios y notas usan **alias legibles** (`usr_ana`, `vid_paella`, `cmt_gracias`) definidos en `specs/01-domain/ids-de-ejemplo.md`; los payloads y contratos usan el id real. Un alias nuevo se agrega a esa tabla antes de usarse.
- **Specs:** viven en `specs/` (índice en `specs/README.md`). Los ADR 0001 a 0009 están **aceptados** (2026-10-12) el 0010 (2026-10-16) y el 0011 (2026-10-17). Un ADR "Propuesto" no habilita código que dependa de él; los que se agreguen nacen así. Las hipótesis sin verificar de cada ADR siguen siendo experimentos pendientes.
- **JDK (decisión de Laura, 2026-10-14):** **25 LTS** (Corretto 25.0.1+8, `.sdkmanrc` en la raíz, toolchain 25 de Gradle); verificado con los tres spikes. Criterio "lo último de lo último" = último **estable**: no se adoptan milestones ni betas (Spring Boot 4.2.0-M2, Kotlin 2.5.0-Beta1) ni release candidates; Kafka 4.4.0 espera el anuncio y el tag `apache/kafka:4.4.0`.
- **Decididos el 2026-10-16 ("sigamos entonces", sobre mis recomendaciones):** persistencia = **JDBC plano con `JdbcTemplate`** (enmienda ADR 0001); `$id` de los JSON Schema = **URN `urn:videoeda:schema:<nombre>`** (enmienda ADR 0002, verificado con `SchemaContractTest`); observabilidad = logs JSON con `correlationId` + **Grafana + Loki** en compose, sin OpenTelemetry (**ADR 0010**, con sus hipótesis aún sin verificar).
- **Aceptado el 2026-10-17:** ADR 0011 (resiliencia y reprocesamiento ordenado; nota 19; escenarios RES-01 a RES-09; G9), verificado en el spike S5 con clientes planos; sus experimentos pendientes siguen abiertos.
- **Decidido el 2026-10-17:** `sequence` = ordinal del evento dentro del stream publicado de ese agregado en ese topic, sin huecos (`VideoPublished` = 1, `VideoDeleted` = 2; ADR 0002 enmendado). Falta verificarlo al implementar el outbox.
- **Worker de procesamiento (2026-10-17, aprobado por Laura sobre mis recomendaciones):** vive en el mismo proceso que la API (`videos.worker.enabled`); usa `KafkaShareConsumer` directo con `RENEW` + `poll` (nota 07), en un solo hilo: el transcoder recibe `keepAlive()` y cada llamada renueva el lock; tope de 3 entregas contado por el worker; DLQ `videos.processing.dlq` con key y partición originales y headers `dlq-*`; transcoder simulado con marcas `[falla-transitoria]` y `[falla-permanente]` en el título; "alerta" = log `ERROR` con `alert=true`. Idempotente por estado del video, sin inbox.
- **Calidad y seguridad (ADR 0012, aceptado 2026-10-17, decisiones de Laura):** repo git propio; SonarQube Community local en el perfil `quality` de Compose + SonarCloud opcional en CI; autenticación con **Keycloak** (OIDC) y JWT; licencia MIT. Fases 0 a 6 en el ADR; la 0 (gitleaks), la 1 (ktlint, detekt, hadolint, ShellCheck; nota 21) la 2 (Loki + Alloy + Grafana detrás de docker-socket-proxy; nota 22, ADR 0010 verificado) y la 3 (CI en `.github/workflows`, Trivy, CodeQL, Dependabot, JaCoCo con piso; nota 23) están hechas, **la 3 sin haberse ejecutado todavía en GitHub**, y la 4 (SonarQube local en el perfil `quality`, `deploy/dev/sonar.sh`; verificación SHA-256 de dependencias de Gradle; nota 24). La fase 5 (autenticación: Keycloak 26.8.1 + JWT en la API; `creatorId` sale del token y es opcional en el body por compatibilidad; solo el dueño borra; ADR 0013, nota 25, escenarios SEC-01 a SEC-18) está hecha. **Ojo:** el shell de esta máquina exporta `DOCKER_DEFAULT_PLATFORM=linux/amd64` en `~/.zshrc`; todo se probó también con `env -u DOCKER_DEFAULT_PLATFORM` (arm64 nativo); SonarQube no arranca emulado. **detekt 1.23.8** es la última estable pero no soporta oficialmente JDK 25 ni Kotlin 2.4 (se fuerza `jvmTarget` 22); se reevalúa al salir una 2.x estable. Todo corre desde imágenes de Docker.
- **Pendiente de decidir:** binary vs structured mode (structured propuesto), nombre final del proyecto.

## Principio rector: menos es más

- Es un **ejemplo bien implementado** de arquitectura orientada a eventos; no un sistema de producción ni un ejercicio de ingeniería.
- Un componente entra solo si demuestra una garantía que el proyecto quiere mostrar. Si no, se difiere y se documenta (ADR 0008, **aceptado** el 2026-10-12).
- Ante una opción "más completa" y otra "más simple", se propone la simple y se deja la otra como camino de crecimiento.
- El `README.md` raíz explica la filosofía y el proceso de creación de specs; se actualiza cuando cambia una decisión.

## Idioma y estilo

- Documentación y conversación en **español**. Identificadores de código, topics, eventos y configs en **inglés**.
- Tono directo. Tablas para comparar, listas para pasos, nada de relleno.
- Responder con recomendación, no con un catálogo de opciones sin postura.

## Convenciones de código (pedido de Laura, 2026-10-14)

- **KISS, siempre (pedido de Laura, 2026-10-19): la solución más simple que cumple el escenario.** Concretamente:
  - Se escribe lo que el escenario o el contrato pide, no lo que "podría hacer falta": nada de abstracciones, interfaces, parámetros, opciones de configuración ni patrones para un uso futuro. Una interfaz nace cuando hay una segunda implementación o cuando el puerto lo exige la arquitectura (ADR 0009), no antes.
  - Ante dos soluciones, se elige la más corta y directa; la otra queda como camino de crecimiento en el ADR o la nota, no en el código.
  - Se usa el lenguaje y la biblioteca estándar antes que una dependencia o un framework nuevo; una dependencia nueva exige que el escenario no se pueda resolver sin ella.
  - Un flujo se lee de arriba hacia abajo sin saltar entre clases: se divide cuando una clase mezcla responsabilidades distintas, no para cumplir un número de funciones.
  - Si el código necesita explicación para entenderse, es señal de que está complicado: se simplifica antes de nombrarlo mejor.
  - Se aplica al código nuevo y también a lo que se toca: no se agrega complejidad nueva a un archivo que se podía dejar más simple.
  - **Arquitectura limpia, no sobredimensionada (Laura, 2026-10-19):** se mantienen las capas (el dominio no conoce frameworks; los puertos están en los bordes reales: base de datos, Kafka, transacción, reloj) y se evita la ceremonia: una clase por caso de uso, objetos de comando que solo repiten parámetros, clases que únicamente reenvían, o tipos intermedios para el mismo dato. Si quitar una pieza no cambia ningún comportamiento ni rompe una frontera, sobra.
- **Archivos cortos (pedido de Laura, 2026-10-19): ningún `.kt` de `services/` pasa de 130 líneas**, ni de producción ni de test. Cuando un archivo crece se divide por responsabilidad (un test por tema con una base común; una clase por rol), no se comprime. `deploy/dev/check-file-length.sh`, dentro de `lint.sh` y de CI, falla si se pasa.
- **Nombres que expresan intención**, tomados del lenguaje ubicuo (`specs/01-domain/glosario.md`): `postComment`, `publishPendingEvents`, `CommentEventStore`. Nada de abreviaturas (`ds`, `c`, `ps`, `rs`, `e`, `ev`, `s`): `dataSource`, `connection`, `statement`, `rows`, `event`, `state`.
- Clases con sustantivo del dominio o rol claro (`CommentViewProjection`, `RowLocking`); métodos con verbo; constantes con significado (`COMMENT_POSTED_TYPE`, no `T_POSTED`).
- **Sin comentarios en el código (pedido de Laura, 2026-10-19): el código se lee como un libro.** Lo dicen los nombres, las funciones chicas, las constantes y los tipos: un `catch (expectedFailure: Exception)` dice que capturar todo es intencional, `findByIdForUpdate` dice que bloquea la fila, `createProcessingTopicSharedByAllWorkers` dice por qué es una sola partición. El porqué de una decisión vive en el ADR, la nota o el escenario que la respalda, y en `because(...)` de Gradle para las restricciones de versión. Aplica a Kotlin, Gradle, SQL, shell, YAML, TOML, Dockerfile y los `spikes/`. Excepciones: el *shebang* y la anotación de versión `# vX.Y.Z` junto al SHA de una acción de GitHub (la mantiene Dependabot). `deploy/dev/check-no-comments.sh`, dentro de `lint.sh` y de CI, falla si aparece uno.
- Un renombrado no cambia comportamiento: se corren los tests del spike o servicio antes y después.
- Los tests se nombran por escenario (`ES-06 ...`, `CTR-01 ...`) y usan variables legibles (`commentId`, `videoId`, `authorId`).
