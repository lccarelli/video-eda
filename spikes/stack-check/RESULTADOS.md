# Resultados del spike S1 y S2

> **Fecha de la corrida:** 2026-10-12 · **Comando:** `./gradlew clean test` (en `spikes/stack-check/`) · **Resultado:** 12 pruebas, 12 en verde, 2 min 29 s con las imágenes ya descargadas · **Nada de esto toca `compose.yaml`**: el broker lo levanta Testcontainers en puertos aleatorios.

## Versiones reales

| Pieza | Versión | Cómo se verificó |
|---|---|---|
| Broker | `apache/kafka:4.3.1`, KRaft de un nodo, imagen `arm64` de 239 MB | `docker image inspect`; el tag existe (`docker manifest inspect`) |
| `kafka-clients` en el classpath | **4.3.1** | `AppInfoParser.getVersion()` = `4.3.1` y `dependencyInsight` |
| Spring Kafka | 4.1.1 (declara `kafka-clients` 4.2.1) | Maven Central, `dependencyInsight` |
| Spring Boot | 4.1.1 (su BOM también fija `kafka-clients` 4.2.1) | `dependencyInsight` |
| Kotlin | 2.4.21 | `build.gradle.kts` |
| Testcontainers | 2.0.5 (`testcontainers-kafka`) | Maven Central |
| Gradle | 9.8.1 (wrapper generado con la imagen `gradle:9-jdk21`; el Gradle del host es 5.6.4 y no sirve) | `gradle-wrapper.properties` |
| JDK | Corretto 21.0.10 (LTS) | `java -version`. **La decisión de JDK del proyecto sigue abierta**: Boot 4.1.1 y Kotlin 2.4.21 compilaron y corrieron sin problema con el toolchain 21 |
| Docker | 28.5.1, Apple Silicon (arm64) | `docker version` |

## Resultados por pregunta

| # | Pregunta | Veredicto | Evidencia |
|---|---|---|---|
| **S1-00** | ¿`kafka-clients` en el classpath es 4.3.x? | **Pasó** | `AppInfoParser.getVersion() = 4.3.1`. `dependencyInsight`: `4.2.1 -> 4.3.1`, "By constraint / By conflict resolution between versions 4.3.1 and 4.2.1" |
| **S1-Z** | Cero mensajes: ¿arrancan los tres consumers en un contexto Spring Boot real? | **Pasó** | El broker los lista como `{s1-classic-group=Classic, s1-kip848-group=Consumer, s1-share-group=Share}` (`Admin.listGroups()`); asignaciones no vacías; 0 mensajes recibidos |
| **S1-O** | Uno: ¿llega un mensaje a cada tipo? | **Pasó** | 1 por tipo, sin duplicado |
| **S1-M** | Muchos: ¿20 mensajes con key distinta llegan una vez cada uno? | **Pasó** | 21 (1 previo + 20) por tipo, 21 keys distintas, sin duplicados |
| **S2-a** | 20 mensajes con 3 consumers de un share group: ¿cada uno se procesa una vez? | **Pasó** | 20 entregas, 20 mensajes distintos. Reparto por miembro `{2=9, 1=6, 0=5}` |
| **S2-b** | ¿Qué hacen ACCEPT, RELEASE y REJECT? | **Pasó** | ACCEPT: 1 entrega. RELEASE: se re-entrega ~0,5 s después con `deliveryCount=2`. REJECT: 1 entrega y no vuelve (observado 8 s) |
| **S2-c1** | Lock vencido, 3 particiones y 3 consumers | **Pasó** | Re-entrega con `deliveryCount=2`, **al mismo miembro** (cada partición tiene un solo miembro asignado) |
| **S2-c2** | Lock vencido, 1 partición y 3 consumers | **Pasó** | Re-entrega a **otro miembro**: `m1 dc=1 @1203 ms`, `m2 dc=2 @3224 ms` con lock de 2000 ms |
| **S2-d1** | ¿`ShareAcknowledgment.renew()` de Spring dentro del listener mantiene el lock? | **Refutado** (la prueba caracteriza el fallo) | 5 `renew()` sin excepción, uno por segundo, con lock de 3 s: el mensaje se re-entregó (`dc=1`, `dc=2` y una tercera entrega) |
| **S2-d2** | ¿`RENEW` con el cliente directo mantiene el lock? | **Pasó** | 5 s de trabajo con lock de 3 s: una sola entrega entre dos workers, un solo `ACCEPT`, `deliveryCount` siempre 1 |
| **S2-e** | ¿`ConsumerRecord.deliveryCount()` se expone y crece? | **Pasó** | `Optional<Short>` con 1, 2 y 3 tras tres `release()`; también visto en S2-b, S2-c y S2-d |
| **S2-f** | `share.auto.offset.reset=earliest` frente al valor por defecto | **Pasó** | Con `earliest` recibe el mensaje anterior al grupo; el grupo de control sin configurar (`latest`) no recibe nada en 8 s |

## Hallazgos que cambian o precisan lo documentado

1. **CTR-08 se cumple** en esta combinación: los tres tipos de consumer arrancan con Spring Kafka 4.1.1 y `kafka-clients` 4.3.1, sin warning de conflicto. El override se hace con una dependencia directa; Gradle resuelve por conflicto a la versión más alta. No hubo ningún choque de transitivas.
2. **`deliveryCount()` sí se expone.** La página de Spring Kafka "Kafka Queues" dice que el conteo "no se expone a las aplicaciones". Con `kafka-clients` 4.3.1 `ConsumerRecord.deliveryCount()` devuelve `Optional<Short>` con valores 1, 2, 3, y llega al listener de Spring. Cierra la discrepancia de la nota 07 en favor de "sí está" (es un valor aproximado, según KIP-932).
3. **`renew()` de Spring no sirve dentro de un listener bloqueado.** `ShareAcknowledgment.renew()` solo encola una confirmación pendiente (visto en el bytecode: `ConcurrentLinkedQueue.offer`); el contenedor la procesa cuando el listener vuelve, que ya es tarde. *El mecanismo exacto es inferencia mía*; lo observado es que 5 `renew()` no evitaron la re-entrega.
4. **`RENEW` con el cliente directo sí funciona, con un patrón concreto:** `acknowledge(record, RENEW)`, `commitSync()` y un `poll()` que devuelve de nuevo el registro renovado. Durante el desarrollo, reutilizar el `ConsumerRecord` viejo para renovar otra vez lanzó `IllegalStateException: The record cannot be acknowledged.`
5. **El paralelismo de un share group depende de las particiones.** Con 3 miembros y 3 particiones cada partición la atiende un solo miembro: un lock vencido se re-entrega al mismo, y solo después de que su hilo vuelva a hacer `poll`. Con 1 partición y 3 miembros los tres compiten y la re-entrega va a otro. El reparto de 20 mensajes quedó desparejo (9/6/5) por la misma razón.
6. **Acks por modo de Spring:** en el modo `EXPLICIT` (el de por defecto) el argumento `ShareAcknowledgment` del listener llega **nulo**; Spring lo avisa con un WARN. Solo en `MANUAL` es utilizable. Una primera versión de la prueba tropezó con esto y los resultados parecían correctos porque la excepción terminaba en un `REJECT` silencioso.
7. **Configuración de grupo antes de que exista el grupo:** `incrementalAlterConfigs` sobre un grupo inexistente funcionó. Pero en una corrida, `describeConfigs` inmediatamente después devolvió los valores por defecto (30000 ms y `latest`); esperar a que el valor sea visible antes de arrancar los consumers es una precaución razonable. Una corrida, sin repetirla a propósito.
8. **Un nodo necesita ajustes del coordinador de share groups:** puse `share.coordinator.state.topic.replication.factor=1` y `share.coordinator.state.topic.min.isr=1` por variables de entorno. **No probé sin ellos**, así que no sé si son imprescindibles en esta imagen. Tampoco hizo falta habilitar ningún flag adicional de feature en el broker (no toqué `share.version` ni `group.coordinator.rebalance.protocols`).
9. **Existe `ShareConsumerRecordRecoverer`** (`REJECTING` por defecto y `RELEASING`), que decide ACCEPT, RELEASE o REJECT ante una excepción. No hay DLQ: según la página de Spring "no hay seek ni integración con dead-letter topic en el contenedor share". No lo ejercité.
10. **Aviso cosmético de Testcontainers:** dice que la imagen es `amd64` aunque la local es `arm64` y corre nativa.

## Consecuencias para los ADR (sin editarlos)

- **ADR 0001 (stack):** CTR-08 queda verificado para Boot 4.1.1 + Spring Kafka 4.1.1 + `kafka-clients` 4.3.1 + broker 4.3.1 en un nodo. La decisión de JDK sigue abierta.
- **ADR 0005 (consumo y share groups):**
  - El texto del worker con `RENEW` debe aclarar que **no funciona con `ShareAcknowledgment.renew()` dentro del listener**; requiere el cliente directo (con `poll()` entre renovaciones) o un lock de duración suficiente.
  - La DLQ propia hay que construirla nosotros (por ejemplo desde el recoverer o el listener): Spring no la ofrece.
  - `deliveryCount()` está disponible para decidir cuándo mandar a la DLQ o alertar.
  - La concurrencia útil del worker es `min(miembros, particiones)` salvo que haya más miembros que particiones; con 3 particiones en `videos.processing`, los 3 primeros workers no compiten entre sí.
- **Nota 07 y nota 09:** quitar de *No verificado* la discrepancia del `deliveryCount()` y agregar el comportamiento de `renew()`.

## Qué no se probó

- **Caída del consumer a mitad del procesamiento** (cierre abrupto del proceso) en un share group.
- **Rebalances y cambio de particiones** con share groups.
- **Límite de entregas** (`share.delivery.count.limit`, 5 por defecto) y el archivado: solo se llegó a `deliveryCount=3`.
- **Rangos de `share.record.lock.duration.ms`** del broker (mínimo y máximo): bajé el mínimo a 1000 ms por entorno para poder probar; no verifiqué el rango por defecto en esta corrida.
- **Spring Kafka con `@KafkaListener` en modo `MANUAL`** (S2 usa contenedores programáticos).
- **Pact y event store (S3 y S4):** no están en este alcance.
- **Rendimiento:** nada se midió.

## Cómo repetirlo

```
cd spikes/stack-check
./gradlew clean test
```
Hace falta Docker y un JDK 21 en la máquina (el `toolchain` del `build.gradle.kts` pide 21; Boot 4.1.1 admite de 17 a 26). El wrapper de Gradle está incluido; la primera corrida descarga las dependencias y la imagen `apache/kafka:4.3.1`.


## Anexo 2026-10-14: JDK 25 LTS

- **Cambio:** `jvmToolchain` pasa de 21 a **25** (Corretto 25.0.1+8, `~/.sdkman/candidates/java/25.0.1.0+8-amzn`, usado vía `JAVA_HOME` sin cambiar el default de sdkman); `.sdkmanrc` en la raíz con `java=25.0.1.0+8-amzn`. Pines sin tocar (Boot 4.1.1, Spring Kafka 4.1.1, kafka-clients 4.3.1, broker 4.3.1, Kotlin 2.4.21, Pact 4.7.5, Testcontainers 2.0.5, Gradle 9.8.1).
- **Resultado:** `./gradlew clean test` con JDK 25: **12 pruebas, 12 en verde** (6 min 51 s, imagen del broker ya descargada). Antes (JDK 21): 12 en verde.
- **Avisos:** ningún warning del compilador de Kotlin (`w:`) con target 25. Avisos de la JVM: `A Java agent has been loaded dynamically` (ByteBuddy/Mockito, "will be disallowed by default in a future release") y `Sharing is only supported for boot loader classes…`.
- **Gradle 9.8.1** detectó el toolchain 25 y corrió con Launcher JVM `25.0.1 (Amazon.com Inc. 25.0.1+8-LTS)`.
- **Compilación en contenedor:** `docker run --platform linux/arm64 … eclipse-temurin:25-jdk` y `gradle:9-jdk25` (JDK `25.0.4.1+1`, Temurin) compilan el módulo con `./gradlew clean testClasses` (BUILD SUCCESSFUL en 48 s y 26 s con caché). `./gradlew build -x test` falla en `:bootJar` ("Main class name has not been configured") **porque el spike no tiene clase `main`**, no por el JDK; un servicio real sí la tiene.

## Anexo 2026-10-14: prueba exploratoria con Kafka 4.4.0

- **Qué se probó:** `kafka-clients` **4.4.0** (Maven Central, publicado 2026-10-06) + broker `apache/kafka:4.4.0-rc4` (único tag de 4.4.0 en Docker Hub: `rc1` a `rc4`; no existe `4.4.0`), con Spring Kafka 4.1.1 y Boot 4.1.1 sin cambios. Comando: `./gradlew clean test -Pkafka=4.4.0 -PkafkaImage=apache/kafka:4.4.0-rc4` (JDK 25). `dependencyInsight`: `kafka-clients 4.2.1 -> 4.4.0`.
- **Resultado:** **12 de 12 en verde** (3 min 35 s). S1: los tres consumers arrancan; S2: ack/RELEASE/REJECT, lock vencido, `RENEW` con cliente directo, `deliveryCount()` y `earliest` se comportan igual que con 4.3.1; `renew()` de Spring dentro del listener **sigue sin** mantener el lock (S2-d1).
- **Diferencia de fuente:** `org.apache.kafka.common.utils.AppInfoParser` **se movió** a `org.apache.kafka.common.utils.internals.AppInfoParser` en 4.4.0 (el spike no compilaba con 4.4.0 hasta usar reflexión sobre ambos nombres). Es una clase interna; relevante solo para código que la importe.
- **Observación sin diferencia clara:** `NOT_LEADER_OR_FOLLOWER` con reintento del producer en un topic recién creado (`s2.f-2`): 1 aparición en la corrida con 4.3.1 y 5 con 4.4.0-rc4. El broker rc4 corrió emulado `amd64` sobre un host arm64 (la imagen se descargó con `DOCKER_DEFAULT_PLATFORM=linux/amd64`), así que no es comparable en tiempos.
- **Estado de la release (leído el 2026-10-14):** la página oficial de descargas lista hasta **4.3.1**; `downloads.apache.org/kafka/4.4.0/` y `archive.apache.org/dist/kafka/4.4.0/RELEASE_NOTES.html` dan 404; `kafka.apache.org/44/` da 404; `dist.apache.org/repos/dist/dev/kafka/` tiene `4.4.0-rc0, rc1, rc3, rc4`. **No encontré un anuncio de GA**: es un jar en Maven Central (y un `rc4` en Docker Hub) de una release que no figura como anunciada.
- **No probado:** `compose.yaml`/`topic-init` con 4.4.0, rebalances, caída abrupta, Kafka Streams.
