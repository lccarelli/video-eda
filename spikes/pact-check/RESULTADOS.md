# Resultados del spike S3 (Pact con Kotlin, JUnit 5 y Boot 4.1)

> **Fecha de la corrida:** 2026-10-12 · **Comandos:** `./gradlew clean test` (13 pruebas, 13 en verde, 9 s en la última corrida) y `./run-broker-flow.sh` (Pact Broker real) · **Alcance:** este módulo; no se tocó `compose.yaml` ni ningún otro archivo del repositorio.

## Versiones reales

| Pieza | Versión | Cómo se verificó |
|---|---|---|
| Pact JVM (`au.com.dius.pact.consumer:junit5`, `provider:junit5`) | **4.7.5** (última estable en Maven Central, `lastUpdated 2026-08-10`) | `maven-metadata.xml` de Maven Central y `./gradlew dependencies` |
| Kotlin / Boot / JDK | 2.4.21 / 4.1.1 (BOM importada y plugin aplicado) / Corretto 21.0.10 | `build.gradle.kts`, `java -version` |
| Jackson | 2.21.5 (el que usa Pact, administrado por la BOM de Boot) y 3.2.1 (`tools.jackson`, el de `json-schema-validator`) conviven | `dependencyInsight` |
| Validador de JSON Schema | `com.networknt:json-schema-validator` **3.0.8** (Draft 7) | Maven Central |
| Pact Broker | `pactfoundation/pact-broker:3.0.0-pactbroker2.121.2` (el del `compose.yaml`) | `/diagnostic/status/heartbeat` → `{"ok":true}` |
| CLI de Pact | `pactfoundation/pact-cli:1.5.0.5` (la etiqueta más reciente numerada, 2026-07-15; `latest` es la misma) | Docker Hub |
| Gradle | 9.8.1 (wrapper copiado de `spikes/stack-check`) | `gradle-wrapper.properties` |

No se usó el plugin de Gradle de Pact: la publicación y `can-i-deploy` se hicieron con la CLI en Docker, y la publicación de resultados con la propiedad `pact.verifier.publishResults` de Pact JVM.

## Resultados por pregunta

| # | Pregunta | Veredicto | Evidencia |
|---|---|---|---|
| **S3-1** | ¿Pact JVM 4.7.5 genera message pacts V4 con Kotlin 2.4.21, JUnit 5 y Boot 4.1.1? | **Pasó** | `FeedConsumerPactTest` y `NotificationsConsumerPactTest`; se escribieron `build/pacts/feed-videos.json` y `notifications-videos.json` (`"pact-jvm": {"version": "4.7.5"}`, `"type": "Asynchronous/Messages"`) |
| **S3-1z** | Cero: un pact sin interacciones | **Pasó** (la librería lo acepta y escribe un pact vacío) | `ZeroInteractionsPactTest`: `messages.size == 0` |
| **S3-1m** | Muchos: dos consumers sobre el mismo evento | **Pasó** | Dos pacts, uno con `title` y `kafka_key` y otro sin ellos; el provider verificó ambos |
| **S3-2a** | El provider cumple los pacts (camino verde) | **Pasó** | `VideosProviderVerificationTest`: `feed - VideoPublished PASSED`, `notifications - VideoPublished PASSED` |
| **S3-2b** | Romper `creatorId` (faltante) falla con mensaje legible (CTR-01) | **Pasó (falla como corresponde)** | `body: $.data Actual map is missing the following keys: creatorId` y el diff del objeto |
| **S3-2c** | Cambiar el tipo de `creatorId` (CTR-01) | **Pasó (falla como corresponde)** | `body: $.data.creatorId Expected 7 to match '^usr_[0-9a-f]{8}-…$'` |
| **S3-2d** | Campo opcional nuevo (CTR-02) | **Pasó** | Ambos pacts verifican con `thumbnailUrl` extra |
| **S3-3** | La key (CTR-05): test propio | **Pasó** | `KeyAndSubjectContractTest`: key == subject == `data.videoId`; y se detecta `key-wrong` |
| **S3-3x** | **Extra, no pedido:** ¿Pact detecta la key si viaja como *metadata* del mensaje? | **Sí, con una condición** | Con `kafka_key` en la metadata del pact de Notifications (con matcher regex), `-Pbreak=key-wrong` falla: `metadata: Expected 'usr_01a13161-…' to match '^vid_…$'`. El pact de Feed, que no declara la key, pasó igual |
| **S3-4** | Validación del payload contra el JSON Schema real del proyecto | **Pasó (ajuste resuelto el 2026-10-16: `$id` URN)** | `SchemaContractTest` (6 pruebas); ver hallazgo 2 sobre `$id` |
| **S3-5a** | Publicar pacts y verificación al Pact Broker real | **Pasó** | `Pact successfully published for feed version 1.0.0 and provider videos`; `Published verification result of 'Ok(…)'` |
| **S3-5b** | `can-i-deploy` verde (Videos 1.0.0) | **Pasó** | `Computer says yes \o/` · `exit code can-i-deploy videos 1.0.0 = 0` |
| **S3-5c** | `can-i-deploy` rojo con `creatorId` faltante (CTR-01) | **Pasó** | `Computer says no` · `The verification for the pact between the version of feed currently in production (1.0.0) and version 1.1.0 of videos failed` · `exit code … = 1` |
| **S3-5d** | `can-i-deploy` con campo opcional (CTR-02) | **Pasó** | `Computer says yes` para Videos 1.2.0 |
| **S3-5e** | `can-i-deploy` con key equivocada declarada en metadata | **Pasó** | `Computer says no` para 1.3.0; solo falla la verificación de Notifications (`SUCCESS? true` para Feed, `false` para Notifications) |

### Tabla de las 13 pruebas de `./gradlew clean test`

| Clase | Prueba | Resultado |
|---|---|---|
| `FeedConsumerPactTest` | CTR-01 feed procesa VideoPublished con creatorId | PASSED |
| `NotificationsConsumerPactTest` | CTR-01 notifications procesa VideoPublished con title | PASSED |
| `ZeroInteractionsPactTest` | CTR-01 cero interacciones | PASSED |
| `KeyAndSubjectContractTest` | CTR-05 la key de videos.video es el videoId y coincide con subject | PASSED |
| `KeyAndSubjectContractTest` | CTR-05 detecta una key equivocada | PASSED |
| `SchemaContractTest` | cero: un objeto vacío no valida | PASSED |
| `SchemaContractTest` | uno: el ejemplo de AsyncAPI valida | PASSED |
| `SchemaContractTest` | muchos: el producer real valida y cada ruptura de CTR-01 falla | PASSED |
| `SchemaContractTest` | CTR-02 un campo opcional nuevo no rompe el schema | PASSED |
| `SchemaContractTest` | un id con UUID versión 4 se rechaza | PASSED |
| `SchemaContractTest` | un `publishedAt` que no es date-time se rechaza | PASSED |
| `VideosProviderVerificationTest` | CTR-01 videos cumple los pacts de sus consumers › feed | PASSED |
| `VideosProviderVerificationTest` | CTR-01 videos cumple los pacts de sus consumers › notifications | PASSED |

## Las rupturas, una por una (provider contra pacts de archivo)

| `-Pbreak=` | Feed (exige `creatorId`) | Notifications (exige `creatorId`, `title`, `kafka_key`) |
|---|---|---|
| *(ninguna)* | OK | OK |
| `creatorId-missing` | **FALLA** (`Actual map is missing the following keys: creatorId`) | **FALLA** (idem) |
| `creatorId-type` | **FALLA** (`Expected 7 to match '^usr_…$'`) | **FALLA** (idem) |
| `extra-optional-field` | OK | OK |
| `key-wrong` | OK (no declara la key) | **FALLA** (`metadata: Expected 'usr_…' to match '^vid_…$'`) |

Salida literal de un fallo (`creatorId-missing`, Feed):

```
Verifying a pact between feed and videos
  [Using File build/pacts/feed-videos.json]
  Given el video ya fue procesado
  VideoPublished
    generates a message which
      has a matching body (FAILED)
      has matching metadata (OK)
Failures:
1) VideoPublished: generates a message which has a matching body
    1.1) body: $.data Actual map is missing the following keys: creatorId
        {
        -  "creatorId": "usr_01a13161-11be-7119-8e3e-bf07f13eb833",
        +  "durationSeconds": 184,
        +  "publishedAt": "2026-10-12T19:29:58Z",
        +  "title": "Mi primer video",
          "videoId": "vid_01a13161-1277-7e7b-9435-b109a7e95989"
        }
```

## Pact Broker real (`run-broker-flow.sh`, log completo en `broker-flow.log`)

Se levantaron solo `postgres` y `pact-broker` con un proyecto de Compose propio (`-p pactcheck`) y un override mínimo (`broker.compose.yaml`: nombres de contenedor propios y sin publicar el puerto de Postgres). `down -v` al final dejó sin contenedores, volumen ni red del proyecto.

| Paso | Resultado |
|---|---|
| Publicar `feed` 1.0.0 y `notifications` 1.0.0 (`--branch main`) | publicados; `Events detected: contract_published, contract_content_changed` |
| `record-deployment` de `feed` y `notifications` 1.0.0 a `production` | `Recorded deployment of feed version 1.0.0 to production environment` |
| Videos 1.0.0 verifica y publica | `Published verification result of 'Ok(…)'` ×2 |
| `can-i-deploy videos 1.0.0 --to-environment production` | **yes**, `exit code = 0` |
| Videos 1.1.0 (sin `creatorId`) | verificación `FAILED`, resultado fallido publicado |
| `can-i-deploy videos 1.1.0` | **no**, `exit code = 1`, con la tabla de la matriz y "The verification … failed" |
| Videos 1.2.0 (campo opcional) | **yes**, `exit code = 0` |
| Videos 1.3.0 (`kafka_key` equivocada) | **no**, `exit code = 1`; falla solo Notifications |

## Hallazgos que cambian o precisan lo documentado

1. **Pact JVM 4.7.5 funciona con esta combinación**: Kotlin 2.4.21, JUnit 5, Boot 4.1.1 (BOM y plugin) y JDK 21. Sin warnings de incompatibilidad. Los `Kotlin stdlib` transitivos de Pact (1.x y 2.x) resuelven a 2.4.21. Detalle de API para Kotlin: `PactDslJsonBody.closeObject()` devuelve un tipo plataforma nulable (hay que llamar sus métodos como sentencias y no encadenarlos), y `@Pact` requiere `toPact(V4Pact::class.java)`.
2. **Los JSON Schema del proyecto usan `$id` relativo** (`"$id": "video-published.json"`) y `networknt 3.0.8` lo rechaza: `InvalidSchemaException: 'video-published.json' no es un válido $id`. Es válido en Draft 7 (un URI-reference), pero esta librería exige una IRI absoluta. El spike lo resuelve en el test sirviendo el contenido con un `$id` absoluto (`https://videoeda.dev/schemas/…`) y resolviendo los `$ref` por nombre de archivo. **Alternativa para el spec:** poner `$id` absoluto en los schemas. No se tocaron.
3. **La key sí se puede verificar con Pact, si viaja como metadata del mensaje.** Lo que la nota 10 dejaba como "posibilidad leída, no probada" quedó probado: el consumer declara `kafka_key` en la metadata (con un matcher regex) y el provider la devuelve en `MessageAndMetadata`. **Condiciones:** (a) el provider del test tiene que informar la key real que usa su producer (en el spike sale del mismo `ProducedRecord`; si el test arma la metadata por separado, la verificación no vale nada); (b) solo la verifica el consumer que la declara, así que no reemplaza a CTR-05 como test propio (hay que exigirla en todos los pacts); (c) un valor fijo en la metadata obliga a que el provider emita el mismo id de ejemplo, por eso se usó un matcher.
4. **El resultado de verificación solo se publica con pacts bajados del broker**, no con pacts de archivo (`@PactFolder`): Pact JVM lo avisa con `Skipping publishing of verification results as it has been disabled`. Por eso hay dos clases: `VideosProviderVerificationTest` (archivo, para el build local) y `VideosProviderBrokerVerificationTest` (broker, para el pipeline), que comparten el contrato en `VideosProviderContract`.
5. **El environment `production` ya existe en el broker 3.0.0**: `create-environment` devolvió `name 'production' is already used by an existing environment`. No hace falta crearlo; sí `record-deployment` de los consumers, sin lo cual `can-i-deploy` del provider no tiene contra qué comparar. Esto cierra el vacío "entorno de Pact (`record-deployment`) para un Compose descartable" en lo que hace al entorno (el broker de un Compose descartable arranca con `production` listo).
6. **Los links HAL del broker usan `PACT_BROKER_BASE_URL`**: con `http://localhost:9292` el CLI en Docker no los alcanza desde otra red. Se resolvió ejecutando la CLI con `--network container:pactcheck-broker`, donde `localhost:9292` es el broker. Alternativa para CI en GitHub Actions: instalar la CLI en el runner y no usar Docker, o usar la red del Compose.
7. **Un mensaje de error del validador no incluye la ruta de la propiedad** para un tipo equivocado (`se encontraron integer, se preveían string`); sí la incluye para una propiedad faltante. Para diagnóstico en CI conviene registrar además la ruta (`error.instanceLocation`, no se probó). Los mensajes salen en el idioma del sistema (aquí, español).
8. **`format: date-time` se valida** por defecto con `networknt 3.0.8` en Draft 7 (`debe ser un RFC 3339 con fecha y hora válido`).
9. **Orden de ejecución:** si los consumers y el provider corren en la misma tarea `test`, la verificación puede leer pacts viejos o ninguno. Se separó en dos tareas de Gradle: `test` (consumers y tests propios) y `providerVerification` (`finalizedBy` de `test`).
10. **El aviso de telemetría de Pact** (`we are tracking events anonymously`) se apaga con `pact_do_not_track=true`; se configuró en las tareas de test.

## Consecuencias para el ADR 0006 y la nota 10 (sin editarlos)

- **ADR 0006:**
  - Pact JVM 4.7.5 queda verificado con Kotlin 2.4.21, JUnit 5 y Boot 4.1.1 para message pacts; el pipeline debe usar `VideosProviderBrokerVerificationTest` y la propiedad `pact.verifier.publishResults`.
  - La receta del gate es: publicar pacts → verificar y publicar resultado → `can-i-deploy --to-environment production` → `record-deployment`. Probada con exit codes 0 y 1.
  - El environment `production` viene creado en el broker 3.0.0.
  - La key puede entrar en Pact vía metadata, pero CTR-05 sigue necesitando su test propio.
- **Nota 10:**
  - Sacar de *No verificado*: "Pact con Kotlin y Spring Boot 4.1", "key por MetadataBuilder" y "entorno de Pact para un Compose descartable".
  - Agregar el hallazgo 3 (key en metadata, con sus condiciones) y el hallazgo 2 (`$id` relativo).
- **Specs de eventos:** ~~decidir si los schemas pasan a `$id` absoluto~~ **Resuelto 2026-10-16:** URN `urn:videoeda:schema:<nombre>` en los 9 schemas; `SchemaContractTest` pasa sin ajuste.

## Qué no se probó

- **Pending pacts y WIP pacts (CTR-03):** no se activaron `enablePending` ni `includeWipPactsSince`.
- **CTR-10 (retirar un tipo viejo mientras un consumidor lo use) y CTR-09 (`type` nuevo en el mismo topic):** no se modelaron.
- **Webhooks, branches múltiples y versiones por commit (`--auto-detect-version-properties`):** solo se usó `--branch main` y versiones fijas escritas a mano.
- **Plugin de Gradle de Pact** (`au.com.dius.pact`): no se usó; todo por CLI en Docker.
- **GitHub Actions:** el flujo corrió local, no en el runner.
- **Un provider real con Kafka:** el productor del spike es una función que devuelve key y valor; no hay `KafkaProducer` ni broker Kafka de por medio.
- **Rendimiento y consumo de memoria del broker de Pact.**

## Cómo repetirlo

```
cd spikes/pact-check
./gradlew clean test                                   # consumers, tests propios y verificación por archivo
./gradlew providerVerification -Pbreak=creatorId-missing   # debe fallar
./run-broker-flow.sh                                   # broker real: publica, verifica y corre can-i-deploy
```
Hace falta Docker y JDK 21. El script usa un proyecto de Compose propio (`pactcheck`) y no toca el stack del repositorio.


## Anexo 2026-10-14: JDK 25 LTS

- **Cambio:** `jvmToolchain` pasa de 21 a **25** (Corretto 25.0.1+8, `~/.sdkman/candidates/java/25.0.1.0+8-amzn`, usado vía `JAVA_HOME` sin cambiar el default de sdkman); `.sdkmanrc` en la raíz con `java=25.0.1.0+8-amzn`. Pines sin tocar (Boot 4.1.1, Spring Kafka 4.1.1, kafka-clients 4.3.1, broker 4.3.1, Kotlin 2.4.21, Pact 4.7.5, Testcontainers 2.0.5, Gradle 9.8.1).
- **Resultado:** `./gradlew clean test` con JDK 25: **13 pruebas, 13 en verde** (37 s). Antes (JDK 21): 13 en verde.
- **Avisos:** ningún warning del compilador de Kotlin (`w:`) con target 25. Avisos de la JVM: `A Java agent has been loaded dynamically` (ByteBuddy/Mockito, "will be disallowed by default in a future release") y `Sharing is only supported for boot loader classes…`.
- **Gradle 9.8.1** detectó el toolchain 25 y corrió con Launcher JVM `25.0.1 (Amazon.com Inc. 25.0.1+8-LTS)`.
- **Nota:** no se probó el flujo del Pact Broker (`run-broker-flow.sh`) en esta corrida; solo `./gradlew clean test` (que incluye `providerVerification`).
