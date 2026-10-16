# ADR 0010: Observabilidad y trazabilidad por `correlationid`

> **Estado:** **Aceptado** (Laura, 2026-10-16: "sigamos entonces", sobre la recomendación presentada) · **Última actualización:** 2026-10-17 (hipótesis del colector, versiones y flujo verificados)

## Contexto
Laura pidió poder ver "logs en cada api, su coherencia, su orden" y trazabilidad de un flujo a través de los servicios, desde un gestor de logs, dentro del `docker compose up` (plug and play, ADR 0007). El ADR 0002 ya define la extensión `correlationid` en cada CloudEvent; falta que cada servicio la use en sus logs. Criterio rector: menos es más (ADR 0008).

## Decisión
1. **Trazabilidad por `correlationid` + `eventId` + `aggregateId` + `sequence`**, sin trazas distribuidas (OpenTelemetry) en esta etapa; queda como camino de crecimiento.
2. **Logs estructurados en JSON a stdout**, una línea por hecho, con campos fijos: `timestamp`, `level`, `service`, `message`, `correlationId`, `eventId`, `aggregateId`, `sequence`, `topic`, `partition`, `offset`.
3. **Propagación:** el `correlationId` nace en el borde HTTP (header `X-Correlation-Id`, o se genera `cor_<UUIDv7>` si falta), viaja en el CloudEvent y el consumer lo restituye al logging (MDC) antes de procesar.
4. **Visor:** **Grafana + Loki** en `compose.yaml` (consulta por `correlationId` entre servicios). Alternativa descartada por ahora: Dozzle (más simple, búsqueda más pobre).
5. **Batería de integración** con Testcontainers sobre el mismo `compose.yaml`; cada escenario de punta a punta verifica que los logs de los servicios compartan el `correlationId` en el orden esperado.

## Alternativas
| Opción | Ventaja | Costo | Veredicto |
|---|---|---|---|
| Solo `docker compose logs` | Cero componentes | Sin filtro por flujo | No cumple lo pedido |
| Dozzle | Casi sin configuración | Búsqueda entre servicios limitada (sin verificar) | Alternativa |
| **Grafana + Loki** | Filtra por campo entre servicios | Dos contenedores y un colector de logs | **Elegida** |
| OpenTelemetry (trazas) | Árbol de spans | Instrumentación y backend extra | Camino de crecimiento |

## Consecuencias
- Cada servicio incluye un formateador JSON y un filtro/interceptor que maneja el MDC.
- `compose.yaml` suma Loki, Grafana y un colector (Alloy detrás de docker-socket-proxy; ver abajo); el requisito sigue siendo solo Docker.
- Los escenarios de integración leen los logs de Loki (o de los contenedores) para afirmar coherencia y orden.

## Sin verificar (experimentos pendientes)
- Que el stack funcione en Linux (CI) y no solo en Docker Desktop para Mac arm64.
- Persistencia de los datos de Loki y Grafana tras `docker compose down` y `up` (los volúmenes existen; no se probó el ciclo).
- Que la retención de 7 días de Loki borre de verdad (no se esperó el plazo).
- Render de los paneles en el navegador: se verificaron las consultas por API, no la pantalla.
- Orden de las líneas entre servicios: el reloj de cada contenedor es el mismo host, pero el orden entre servicios se afirma por `sequence` y causalidad, no por el timestamp.
- Logs de los consumers de Interactions, Feed y Notifications: todavía no existen.

## Verificado (2026-10-17)
- **Salida JSON de Boot 4.1.1:** el formato `ecs` incluye los campos del MDC (`correlationId`, `eventId`, `aggregateId`, …) sin código propio (`VideosApplicationTest`).
- **Propagación del `correlationId`:** nace en el header `X-Correlation-Id` (o se genera), aparece en el log del controller, en el del `OutboxPublisher`, en el del worker y en el CloudEvent de `videos.processing` y de `videos.video`.
- **Colector (decisión):** **Grafana Alloy 1.20.1** con `discovery.docker` y `loki.source.docker`, detrás de **docker-socket-proxy 0.5.0** (solo lecturas de contenedores y redes; imágenes, volúmenes, creación y `exec` devuelven 403). Promtail no se evaluó; el driver de logging de Loki exige instalar un plugin en el motor y rompe "solo Docker".
- **Versiones:** Loki 3.7.8, Grafana 13.2.4 (fuente: releases de GitHub, consultadas 2026-10-17).
- **Flujo extremo a extremo:** un `POST /videos` con `X-Correlation-Id` conocido se recupera en Loki, a través de Grafana, con las 4 líneas del flujo (video aceptado, `process-video` publicado, procesado por el worker, `VideoPublished` publicado).
- **Plataforma:** la primera verificación (2026-10-17) corrió con imágenes **amd64 emuladas**, porque el shell de esta máquina exporta `DOCKER_DEFAULT_PLATFORM=linux/amd64`; el ADR decía "arm64 sin emulación" y era incorrecto. Luego se repitió con `env -u DOCKER_DEFAULT_PLATFORM`, con las imágenes arm64 nativas de Loki, Alloy, Grafana y Videos, y el flujo extremo a extremo pasó igual (`deploy/dev/smoke.sh`).
- **Seguridad del stack:** Grafana sin acceso anónimo, sin registro y sin `admin/admin`, publicado solo en `127.0.0.1`; Loki no se publica; la red entre el proxy y Alloy es interna (sin internet).
- Detalle y fuentes en la [nota 22](../../docs/study/22-observabilidad-loki-grafana.md).

## Respaldo
ADR 0002 (envelope y `correlationid`), ADR 0007 (infraestructura), ADR 0008 (alcance mínimo), nota 17 (`cor_`).
