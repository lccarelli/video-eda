# ADR 0001: Stack tecnológico

> **Verificado el 2026-10-12 (spike S1, CTR-08):** Boot 4.1.1 + Spring Kafka 4.1.1 + `kafka-clients` 4.3.1 + broker 4.3.1 arrancan con los tres tipos de consumer. Ver nota 18.
> **Enmienda 2026-10-14 (decisión de Laura, "quiero usar lo último de lo último"):** **JDK 25 LTS** (Corretto 25.0.1+8). Verificado: los tres spikes (12 + 13 + 18 pruebas) pasan con JDK 25 sin cambiar los demás pines; imágenes `gradle:9-jdk25` y `eclipse-temurin:25-jdk` disponibles. Versiones de ese día: Boot 4.1.1, Spring Kafka 4.1.1 y Kotlin 2.4.21 son los últimos estables (4.2.0-M2 y 2.5.0-Beta1 son milestone y beta, no se adoptan). **Kafka 4.4.0:** el cliente está en Maven Central (2026-10-06) pero no encontré anuncio de GA ni imagen `apache/kafka:4.4.0` (solo `rc1` a `rc4`); la prueba con `rc4` pasó S1, S2 y la parte Kafka de S4 (nota 18, anexo). Se mantiene 4.3.1 hasta que exista el tag estable.
> **Estado:** Aceptado (Laura, 2026-10-12; opción A) · **Última actualización:** 2026-10-14

## Contexto
Queremos usar lo más reciente disponible. Spring Kafka 4.1.1 soporta share groups en producción y el protocolo nuevo de consumer (KIP-848), pero su matriz oficial empareja con `kafka-clients` 4.2.x, mientras que el broker más nuevo es 4.3.1 (nota 09).

## Decisión

| Pieza | Versión | Observación |
|---|---|---|
| Lenguaje | Kotlin 2.4.21 | |
| Framework | Spring Boot 4.1.1 | Java 17 a 26, Spring Framework 7.0.9+ |
| Cliente Kafka | Spring Kafka 4.1.1 con `kafka-clients` sobrescrito a **4.3.x** | Fuera de la matriz oficial; se cubre con test de arranque |
| Broker | Apache Kafka **4.3.1**, modo KRaft | |
| Build | Gradle (Kotlin DSL) 9.x | Boot pide 8.14+ o 9.x |
| Pruebas con broker | Testcontainers con imagen de Kafka 4.3.1 | Ver verificación |

- Share groups para trabajo independiente y `group.protocol=consumer` para flujos con orden (ADR 0005).
- La misma versión menor de cliente y broker, **4.3**, en todos los servicios (nota 07, regla 6).

## Consecuencias
- Estamos fuera de la combinación documentada por Spring. El riesgo se acota con un test que arranca un consumer clásico, uno con `group.protocol=consumer` y un share consumer contra el broker 4.3.1.
- Cada actualización de Spring Kafka obliga a revisar si la matriz ya incluye 4.3.
- Las limitaciones de share consumers en Spring aplican tal cual: sin DLQ integrada, sin message converters, sin batch listeners.

## Alternativas descartadas
- **B. Broker 4.2.2 con el cliente que trae Spring:** emparejado oficialmente, pero no es la versión más nueva.
- **C. Spring Kafka 4.2.0-M2:** es un milestone; no lo usamos para un repositorio de demostración.
- **Otros lenguajes con `librdkafka`:** soporte de KIP-848 y KIP-932 sin verificar; Kafka Streams no existe fuera de la JVM.

## Verificación (obligatoria antes de dar por cerrado este ADR)
1. Test de arranque: crea un consumer clásico, uno con `group.protocol=consumer` y un share consumer contra Kafka 4.3.1; falla si alguno no arranca.
2. Test que lee la versión efectiva de `kafka-clients` del classpath y falla si no es 4.3.x.
3. Experimento: confirmar el `deliveryCount()` (discrepancia abierta en nota 07).

## Enmienda 2026-10-16: capa de persistencia
**JDBC plano con `JdbcTemplate`** (decisión de Laura, "sigamos entonces", sobre mi recomendación). Motivo: el spike S4 sostuvo el event store, el CHECK de ids, la proyección en la misma transacción y el publisher con JDBC plano; no hace falta un ORM. Spring Data JDBC no se probó con Boot 4.1.1 y queda fuera.

## Enmienda 2026-10-17: versiones que fija el BOM de Spring Boot 4.1.1
Con el BOM de Boot 4.1.1 el servicio Videos resuelve **Spring Framework 7.0.9**, **Flyway 12.4.0** (la última publicada es 13.10.3; se sigue la combinación soportada por Boot), **JUnit 6.0.3**, Testcontainers 2.0.5, HikariCP 7.0.2 y **Jackson 3.1.5** (`tools.jackson`) para el runtime; los spikes usan Jackson 2 y JUnit 5.14.4. Se usa `JdbcClient` (la fachada fluida de `spring-jdbc`) en vez de `JdbcTemplate` directo; el módulo es el mismo. Verificado: Flyway 12.4.0 migra sobre PostgreSQL 18.6 sin advertencias.

## Pendiente
- **JDK:** decidido el 2026-10-14: **25 LTS** (Boot admite 17 a 26; Kotlin 2.4 llega a bytecode de Java 26). Es el último LTS: 26 y 27 no son LTS.
- Que Spring Kafka 4.1.1 funcione sin problemas con `kafka-clients` 4.3.x: no verificado.
- Spring Boot: propiedades de autoconfiguración para share consumers: no verificado.

## Respaldo
- Nota 09 (clientes y versiones), nota 07 (share groups), nota 06 (novedades de Kafka 4.x).
- [Kafka downloads](https://kafka.apache.org/community/downloads/) · [Spring Kafka](https://spring.io/projects/spring-kafka) · [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html) · [Kotlin releases](https://kotlinlang.org/docs/releases.html), consultados 2026-10-12.
