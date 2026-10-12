# ADR 0002: Envelope, contrato y payload de eventos

> **Estado:** Aceptado el 2026-10-12 por Laura · **Actualizado el 2026-10-12 por el ADR 0008 (aprobado):** JSON Schema sin Avro ni registry; `causationid` diferido. **Formato de ids decidido el 2026-10-12** (nota 17).

## Contexto
Un evento necesita tres capas: envelope con metadatos, payload con datos y contrato que describe canales y mensajes. El contrato es lo que se verifica en CI (nota 01).

## Decisión

| Capa | Elección | Estado |
|---|---|---|
| Envelope | **CloudEvents 1.0** | Decidido |
| Contrato | **AsyncAPI 3.1.0**, un archivo por contexto **productor** | Decidido; validado con la CLI oficial el 2026-10-12 |
| Payload | **JSON Schema**, sin Avro ni Schema Registry | Decidido (ADR 0008) |
| Modo de CloudEvents sobre Kafka | **Structured** | Propuesta nuestra; con polling publisher es trivial de verificar |
| JSON:API | No se usa para eventos | Decidido |

### Atributos obligatorios
`id`, `source`, `type`, `specversion`, `time`, `subject`, más datacontenttype `application/json`.

### Extensiones propias
| Extensión | Para qué | Estado |
|---|---|---|
| `correlationid` | Agrupa todos los eventos de una saga o de un request | Propuesta |
| `causationid` | `id` del evento que causó este | **Diferida** (ADR 0008): ningún escenario la exige |
| `sequence` | **Ordinal del evento dentro del stream publicado de ese agregado en ese topic** (1, 2, 3…, sin huecos), asignado por el outbox o el event store al insertar; sirve para descartar repetidos y viejos y apartar huecos (ADR 0011) | **Decidida (Laura, 2026-10-17)** |

### Convenciones
- `type`: `dev.videoeda.<contexto>.<evento>`, **sin sufijo de versión** (decisión de Laura, 2026-10-12). Un cambio incompatible crea un `type` nuevo con sufijo `.v2` (y `.v3`, etc.); la ausencia de sufijo equivale a la versión 1. Respaldo: Zalando #213 (versión opcional) y CloudEvents primer (se puede no incluir versión en el `type`).
- `source` + `id` es la clave de deduplicación del consumer (ADR 0005).
- `id`: `evt_<UUIDv7>`, único por evento y estable en reenvíos. `subject`: el id del agregado, sin tipo adicional (`cmt_<UUIDv7>`, no `comment/cmt_…`). `correlationid`: `cor_<UUIDv7>`. `source`: `/<contexto>`, por ejemplo `/interactions`. Los ids de agregado son `<prefijo>_<UUIDv7>` (`vid`, `cmt`, `usr`, `ntf`) y los genera el servicio dueño (nota 17, decisión de Laura, 2026-10-12).
- Evolución (nota 12):
  - Cambio aditivo y compatible: mismo `type`; cambia la versión del `dataschema`.
  - Cambio incompatible: **nuevo `type` en el mismo topic**; ambos tipos se emiten durante la migración y se retira el viejo cuando ningún pact lo usa.
  - Los consumidores ignoran los `type` desconocidos.
  - El topic **no** lleva versión (ADR 0003).
- **No se usa Schema Registry** (ADR 0008). Si en el futuro se adopta, la nota 12 documenta las dos opciones y la discrepancia entre fuentes.
- Los eventos con `type` desconocido se ignoran pero se **cuentan** y alertan si el volumen crece (el fallo silencioso es el riesgo de este enfoque).

## Consecuencias
- Cada evento tiene su schema y su mensaje en AsyncAPI; el schema es la fuente del contract test.
- Structured mode (todo el evento en el valor) simplifica los contract tests y el outbox; no aprovecha headers para filtrar sin deserializar.
- JSON Schema es legible y se integra con Pact para mensajes, a costa de menos compacidad que Avro y de no tener la verificación de compatibilidad de un registry; la cubren Pact y la validación en CI (ADR 0006).

## Alternativas
| Opción | Ventaja | Costo |
|---|---|---|
| Avro + Schema Registry | Compatibilidad verificada por el registry, payload compacto | **Descartada** (ADR 0008): otro componente a operar; el contrato viviría en el registry y en AsyncAPI |
| CloudEvents en binary mode | Atributos en headers, payload limpio | Headers y outbox más difíciles de probar; ver spike |
| Envelope propio | Control total | Se reinventa un estándar |

## Verificación
- Test que valida cada evento de ejemplo contra su schema.
- Test que falla si un topic de AsyncAPI no declara `key`.
- Test de que el polling publisher publica el envelope completo en structured mode (ADR 0004).

## Pendiente
- ~~`v1` desde el inicio o solo ante un cambio incompatible~~ Resuelto: sin `v1`.
- Alternativa a emitir dos tipos a la vez: upcasting o downcasting en la lectura.
- Structured vs binary mode.
- Nombre final de las extensiones (`correlationid`, `sequence`) y su tipo.
- ~~Formato de `id` (UUID v7 propuesto)~~ Resuelto el 2026-10-12: `evt_<UUIDv7>` (nota 17).

## Enmienda 2026-10-16: `$id` de los JSON Schema
Los schemas usan **URN** como `$id` y como `$ref` entre sí: `urn:videoeda:schema:<nombre>` (por ejemplo `urn:videoeda:schema:video-published`). Motivo: `networknt 3.0.8` rechaza el `$id` relativo (spike S3, hallazgo 2). Verificado: `SchemaContractTest` (6 pruebas) pasa resolviendo las URN a `specs/03-events/schemas/` sin reescribir archivos. Los `$ref` de AsyncAPI a archivos (`../schemas/x.json`) no cambian.

## Enmienda 2026-10-17: significado de `sequence`
`sequence` es el **ordinal del evento dentro del stream publicado de ese agregado en ese topic** (1, 2, 3…), sin huecos. No cuenta transiciones internas del agregado: `Video` pasa por `UPLOADED` y `PROCESSING` sin publicarlos, y `VideoPublished` lleva `sequence: 1`. Lo asigna la misma transacción que escribe el evento (outbox o event store). Motivo: el guard estricto de los consumers (ADR 0011, nota 19) exige contigüidad sobre lo que el consumer realmente recibe. Si un mismo hecho se publica en dos tipos (`.v2`, nota 12), ambos llevan el mismo `sequence` y cada consumer ve una secuencia contigua de su tipo.

## Respaldo
Nota 01 (envelope y contrato), nota 05 (outbox y CDC), nota 03 (duplicados).
