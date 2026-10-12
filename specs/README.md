# Specs

Índice de especificaciones. Se actualiza en cada turno en que cambie una spec o un ADR. Reglas y método en [`../docs/metodo-de-trabajo.md`](../docs/metodo-de-trabajo.md); estudio de respaldo en [`../docs/study/README.md`](../docs/study/README.md).

## Estado

| Documento | Estado | Respaldo |
|---|---|---|
| [00-vision](00-vision.md) | Borrador | — |
| [01-domain/event-storming](01-domain/event-storming.md) | Borrador | Notas 02, 07, 08 |
| [01-domain/bounded-contexts](01-domain/bounded-contexts.md) | Borrador | Notas 01, 07, 08 |
| [01-domain/glosario](01-domain/glosario.md) | Borrador | Todas |
| [01-domain/ids-de-ejemplo](01-domain/ids-de-ejemplo.md) | Vigente (alias → id real de los ejemplos) | Nota 17 |
| [ADR 0001 – Stack](02-adr/0001-stack.md) | **Aceptado** | Notas 06, 07, 09 |
| [ADR 0002 – Envelope y contrato](02-adr/0002-envelope-y-contrato.md) | Aceptado (2026-10-12) | Notas 01, 03, 05 |
| [ADR 0003 – Ordering y keys](02-adr/0003-ordering-y-keys.md) | Aceptado (2026-10-12) | Notas 02, 04 |
| [ADR 0004 – Outbox y polling publisher](02-adr/0004-publicacion-outbox.md) | Aceptado (2026-10-12; actualizado) | Notas 03, 05 |
| [ADR 0005 – Consumo y share groups](02-adr/0005-consumo-y-share-groups.md) | Aceptado (2026-10-12) | Notas 02, 03, 04, 07, 09 |
| [ADR 0006 – Contract testing](02-adr/0006-contract-testing.md) | Aceptado (2026-10-12) | Notas 01, 02, 10 |
| [ADR 0007 – Infraestructura y despliegue](02-adr/0007-infraestructura-y-despliegue.md) | Aceptado (2026-10-12; actualizado: Docker Compose) | Notas 05, 09, 10, 11 |
| [ADR 0008 – Alcance mínimo](02-adr/0008-alcance-minimo.md) | **Aceptado** (revisó 0002, 0004, 0005, 0006 y 0007) | Notas 05, 07, 11, 12, 13 |
| [ADR 0009 – DDD táctico, CQRS y event sourcing de `Comment`](02-adr/0009-ddd-cqrs-y-event-sourcing.md) | Aceptado (2026-10-12; enmienda el alcance del 0008) | Notas 14, 15, 16 |
| [ADR 0011 – Resiliencia, volumen y reprocesamiento ordenado](02-adr/0011-resiliencia-y-reprocesamiento.md) | Aceptado (2026-10-17) | Nota 19, ADR 0005 |
| [ADR 0010 – Observabilidad y trazabilidad por `correlationid`](02-adr/0010-observabilidad-y-trazabilidad.md) | Aceptado (2026-10-16) | ADR 0002, 0007, 0008 |
| [ADR 0012 – Calidad y seguridad desde el inicio](02-adr/0012-calidad-y-seguridad-desde-el-inicio.md) | Aceptado (2026-10-17; enmienda la línea de seguridad del `docs/metodo-de-trabajo.md`) | ADR 0007, 0008, 0010 |
| [ADR 0013 – Autenticación y autorización de la API](02-adr/0013-autenticacion-y-autorizacion-de-la-api.md) | Aceptado (2026-10-18; desarrolla la fase 5 del ADR 0012) | ADR 0012, 0009, nota 17 |
| [07-nfr/garantias](07-nfr/garantias.md) | Borrador | Notas 02, 03, 05, 10 |
| [03-events](03-events/README.md) | Borrador (3 AsyncAPI 3.1.0 y 9 schemas, validados; cuatro topics: `videos.video`, `interactions.comment`, `feed.follow`, `videos.processing`) | Notas 01, 12, 13 |
| [06-scenarios](06-scenarios/README.md) | Borrador (112 escenarios: 74 previos, 32 de CQRS, agregados y event sourcing, y 6 de `feed.follow`; regla de publicación aplicada; abierto: valor N del backfill y retención de `feed.follow` a confirmar; ZOMBIES Z-O-M aplicado) | Notas 02, 03, 04, 07, 08, 10 |

## Por crear
- `04-apis/`: OpenAPI de los endpoints síncronos.
- `05-contracts/`: contratos consumer-driven (ya hay nota de respaldo, falta el experimento).
- Pipeline de CI concreto (jobs y orden) sobre Docker Compose: fase 3 del ADR 0012.
- Servicios y `Dockerfile` multi-etapa para sumarlos a `compose.yaml`; spikes S1 a S5 (nota 18).
- ADR de persistencia del process manager y de la retención del inbox.

## Flujo de aprobación
Borrador → revisión de Laura → **Aceptado**. Un ADR "Propuesto" no habilita escribir código que dependa de él. Hoy están todos **Aceptados**; las hipótesis sin verificar que contienen figuran como experimentos pendientes dentro de cada ADR.
