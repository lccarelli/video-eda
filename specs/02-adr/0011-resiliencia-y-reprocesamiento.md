# ADR 0011: Resiliencia, volumen y reprocesamiento ordenado

> **Estado:** **Aceptado** (Laura, 2026-10-17: "aceptado, arrancá con Videos"; propuesto el 2026-10-16 a pedido de ella: "patrones resilientes… que todo lo que escribimos sea correcto, resiliente y robusto") · **Última actualización:** 2026-10-17

## Contexto
El ADR 0005 define reintentos y DLQ por consumer, pero la nota 19 encontró un hueco: apartar a la DLQ solo el mensaje fallido **rompe el orden por key** si los siguientes de esa key se siguen aplicando (el `UPDATE` de una edición sobre una fila que no existe se pierde sin error). Además faltan políticas de volumen y de dependencia caída. Criterio rector: menos es más (ADR 0008); sin dependencias nuevas si Spring Framework 7 ya lo resuelve.

## Decisión propuesta
Patrón base: **DLQ secuenciada** (la documentada por Axon Framework: se aparta el evento fallido y todos los siguientes de la misma secuencia, y se reintenta la secuencia entera en orden). Nuestra variante **no guarda estado de secuencias apartadas**: lo infiere del hueco de `sequence`.
1. **Clasificar la falla** en: mensaje permanente (DLQ directa), mensaje transitorio (reintento bloqueante acotado, backoff exponencial con jitter, luego DLQ) y **dependencia caída** (pausar el container; no apartar mensajes sanos).
2. **Guard estricto de `sequence`** en los consumers que dependen del orden por agregado: se aplica solo `última + 1`; `seq <= última` se descarta; un hueco va a la DLQ con causa `SEQUENCE_GAP`. Condición: `sequence` contigua por agregado (verificada solo para `Comment`; Video y Follow requieren definirlo en el outbox).
3. **DLQ con key original**, mismo número de particiones que el topic de origen y headers de origen.
4. **Redrive en orden por el mismo handler** (inbox + guard + efecto en una transacción), herramienta de operación; no reinyecta al topic principal.
5. **Replay** = grupo inactivo + `--reset-offsets` + vaciar read model **y su inbox**; los efectos externos solo se reproyectan si son idempotentes (Notifications: no reproyectable).
6. **Spring Framework 7 core** (`@Retryable` para llamadas a proveedores, `@ConcurrencyLimit` para el tope hacia afuera); **sin Resilience4j ni circuit breaker**.
7. **Decisiones explícitas y registradas:** omitir un evento (aceptar un hueco) y descartarlo de la DLQ son acciones manuales con causa y autor; nunca un default. La DLQ tiene alerta por profundidad y por antigüedad del evento más viejo (en Axon, un desborde frena un segmento entero).
8. **Volumen:** sin cuotas ni ajustes de fetch/linger hasta medir; se observa lag por grupo y profundidad de cada DLQ.

## Alternativas descartadas
Resequencer con buffer (solo si el faltante viene atrasado por otro camino; no es nuestro caso dentro de una partición), Parallel Consumer en modo `KEY` (sin DLQ terminal y sin mantenimiento), DLQ en tabla de la base (variante válida, pierde visibilidad en Kafbat; se reevalúa en el spike), retry topics para consumers ordenados (pierden el orden, nota 04), tabla de keys en cuarentena (estado extra y carreras con el redrive), Resilience4j (sin starter oficial para Boot 4 verificado; Azure desaconseja el breaker con mensajería), parar la partición (frena keys sanas; queda como opción de operación).

## Consecuencias
- El spec de cada consumer declara su política (regla 1 de la nota 04) y su clase de falla.
- Se agrega una herramienta de redrive y un header/columna de causa en la DLQ.
- Cambia la regla de la nota 04 "retry bloqueante de 3 intentos y a la DLQ": sigue valiendo, **más** el guard estricto.
- Escenarios RES-01 a RES-09 (`specs/06-scenarios/11-resiliencia.md`) y garantía G9.

## Verificado en el spike S5 (2026-10-16, clientes planos, Kafka 4.3.1 y PostgreSQL 18.6; `spikes/resilience-check/RESULTADOS.md`)
Guard estricto + DLQ con key y partición originales + redrive en orden por el mismo handler (RES-01 a RES-04, RES-07), pausa con `poll()` ante dependencia caída (RES-05) y su contrafactual, duplicados por caída antes del commit absorbidos por el inbox, e inundación sin pérdida. El caso que falla (guard de la nota 03 y sin guard) se reprodujo.

## Sin verificar (experimentos pendientes; el ADR se aceptó con estos pendientes anotados)
1. Carrera entre el consumer en vivo y el redrive; `kill -9`; rebalances; varios consumers; quién decide **omitir** un evento aceptando un hueco y cuándo se purga la DLQ.
2. ~~Contigüidad de `sequence` en el outbox de Video y Follow~~ **Decidido el 2026-10-17 (Laura, "dale con tu recomendación de sequence"):** `sequence` = ordinal del evento dentro del stream **publicado** de ese agregado en ese topic (`VideoPublished` = 1, `VideoDeleted` = 2), asignado por el outbox al insertar (ADR 0002, enmienda). Motivo: los ejemplos anteriores (3 y 4) contaban transiciones del agregado y un consumer con guard estricto habría apartado el primer evento como hueco. Aplicado a AsyncAPI, schema del envelope, notas 01 y 12 y fixtures de `spikes/pact-check`. **Falta verificarlo** al implementar el outbox (la asignación del ordinal en la misma transacción y sin huecos).
3. `ContainerPausingBackOffHandler` o `pause()` ante un proveedor caído, sin rebalance.
4. `@ConcurrencyLimit` y `@Retryable` con Boot 4.1.1 (qué Spring Framework trae).
5. Replay con `--reset-offsets` y reproyección de `feed_entry` (G8, pendiente de S4).

## Respaldo
Axon Framework (DLQ secuenciada), EIP Resequencer, Ecotone (huecos), nota 19 (resiliencia), nota 04 (reintentos y DLQ), nota 03 (inbox y guard), nota 14 (reproyección), ADR 0004, 0005, 0008, 0009.
