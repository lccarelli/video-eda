# Agregados e invariantes

> **Estado:** borrador para revisión · **Última actualización:** 2026-10-12 · **Contextos:** todos · **Notas:** 15, 08, 03 · **ADR:** 0003, 0009 (aceptado)

Criterios de aceptación de la frontera de cada agregado y de sus invariantes (nota 15). Cada invariante se prueba **sin infraestructura** (`@unit`): el agregado no depende de Spring, Kafka ni JDBC. El orden de implementación es ZOMBIES (agregado nuevo, un cambio, muchos cambios).

## Agregados e invariantes

| Agregado | Contexto | Identidad (= key) | Invariantes | Escenarios |
|---|---|---|---|---|
| `Video` | Videos | `videoId` | Solo `UPLOADED → PROCESSING → PUBLISHED \| FAILED` y `PUBLISHED → DELETED`; `VideoPublished` ocurre una vez; un video borrado no vuelve | AGG-04 a AGG-06, PUB-02 |
| `Rating` | Interactions | `videoId:raterId` | Una puntuación vigente por par; `sequence` crece; valor en rango *(1 a 5, **supuesto**)* | AGG-07 a AGG-09, RAT-05 |
| `Comment` | Interactions | `commentId` | Un comentario borrado no se edita ni se borra de nuevo; `sequence` crece en 1 por cambio; texto no vacío *(**supuesto**)* | COM-02, COM-05, ES-05, ES-09 |
| `Follow` | Feed | `followerId:creatorId` | Una relación por par; follow y unfollow se alternan; `sequence` crece | AGG-10 a AGG-12, FEED-03 |
| `Notification` | Notifications | `notificationId` | `REQUESTED → SENT \| FAILED \| CANCELLED`; una `SENT` no pasa a `CANCELLED`; una por destinatario y causa | AGG-13 a AGG-16, NOT-10 |

**No son agregados** (nota 15): resumen de puntuaciones, videos publicados y entrada de feed. Son proyecciones sin repositorio de dominio; solo tienen unicidad (FEED-11).

## Frontera y concurrencia

```gherkin
@many @unit @G1
Escenario: AGG-01 los comentarios concurrentes sobre un mismo video no compiten
  Dado que 100 viewers distintos comentan vid_paella al mismo tiempo
  Cuando cada PostComment guarda su propio agregado Comment
  Entonces los 100 se confirman sin conflicto de versión
  Y ninguno modifica el agregado Video de vid_paella
```

```gherkin
@one @unit @contrato
Escenario: AGG-02 un refactor interno no cambia el payload publicado
  Dado que Videos mapea el evento de dominio a CloudEvent con una función explícita
  Cuando el campo title de la entidad Video se renombra a name
  Entonces el payload de VideoPublished sigue cumpliendo su JSON Schema
  Y los consumidores no cambian
```

```gherkin
@one @integracion
Escenario: AGG-03 puntuar con el read model desactualizado
  Dado que Interactions aún no procesó VideoDeleted de vid_paella
  Cuando usr_ana puntúa vid_paella
  Entonces la puntuación se acepta, porque el read model local dice PUBLISHED
  Y al procesarse VideoDeleted, las puntuaciones de vid_paella dejan de contarse (RAT-07)
```

## Video

```gherkin
@zero @unit
Escenario: AGG-04 un video recién subido
  Dado que un creator sube un archivo válido
  Cuando se crea el agregado Video
  Entonces su estado es UPLOADED
  Y todavía no se produjo VideoPublished
```

```gherkin
@one @unit
Escenario: AGG-05 camino feliz de las transiciones
  Dado que vid_paella está UPLOADED
  Cuando pasa a PROCESSING y luego se publica
  Entonces su estado es PUBLISHED
  Y se produjo exactamente un VideoPublished
```

```gherkin
@many @unit
Escenario: AGG-06 transiciones inválidas
  Dado que vid_paella está PUBLISHED
  Cuando se intenta pasarlo a PROCESSING, publicarlo otra vez o, estando DELETED, publicarlo
  Entonces cada intento se rechaza
  Y no se produce ningún evento nuevo
```

## Rating

```gherkin
@one @unit
Escenario: AGG-07 una puntuación
  Dado que usr_ana no puntuó vid_paella
  Cuando usr_ana puntúa vid_paella con 4
  Entonces hay una puntuación vigente con valor 4 y sequence 1
```

```gherkin
@many @unit
Escenario: AGG-08 volver a puntuar reemplaza, no suma
  Dado que usr_ana puntuó vid_paella con 4
  Cuando usr_ana puntúa vid_paella con 2
  Entonces hay una sola puntuación vigente, con valor 2
  Y la sequence es 2
```

```gherkin
@unit @pendiente
Escenario: AGG-09 valor fuera de rango
  Dado que el rango válido es de 1 a 5  # supuesto de la nota 15; RAT-01 no lo fija
  Cuando usr_ana intenta puntuar vid_paella con 0 o con 6
  Entonces la operación se rechaza y no cambia la puntuación vigente
```

## Follow

```gherkin
@one @unit
Escenario: AGG-10 follow crea la relación
  Dado que usr_ana no sigue a usr_lucia
  Cuando usr_ana sigue a usr_lucia
  Entonces existe una relación activa con sequence 1
  Y el agregado devuelve CreatorFollowed como evento de dominio, que la capa de aplicación mapea al CloudEvent de feed.follow
```

```gherkin
@many @unit @G1
Escenario: AGG-11 follow y unfollow se alternan
  Dado que usr_ana sigue a usr_lucia
  Cuando deja de seguirlo y vuelve a seguirlo
  Entonces la relación está activa con sequence 3
  Y nunca hubo dos relaciones para el par
  Y el agregado devolvió CreatorFollowed(1), CreatorUnfollowed(2) y CreatorFollowed(3), que comparten la key followerId:creatorId
```

```gherkin
@many @unit @pendiente
Escenario: AGG-12 follow repetido
  Dado que usr_ana ya sigue a usr_lucia
  Cuando usr_ana vuelve a seguir a usr_lucia
  Entonces sigue habiendo una sola relación para el par
  # Sin decidir si el segundo follow se rechaza o es un no-op.
```

## Notification

```gherkin
@zero @unit
Escenario: AGG-13 una notificación nueva
  Dado que se solicita avisar a usr_ana por la publicación de vid_paella
  Cuando se crea la Notification
  Entonces su estado es REQUESTED
```

```gherkin
@one @unit
Escenario: AGG-14 envío exitoso
  Dado que ntf_alfa está REQUESTED
  Cuando el proveedor confirma el envío
  Entonces ntf_alfa queda SENT
```

```gherkin
@many @unit
Escenario: AGG-15 una notificación enviada no se cancela
  Dado que ntf_alfa está SENT
  Cuando se procesa VideoDeleted del video asociado
  Entonces ntf_alfa sigue SENT
  Y no pasa a CANCELLED
```

```gherkin
@many @unit @G4
Escenario: AGG-16 una notificación por destinatario y causa
  Dado que ya existe una Notification para usr_ana por el VideoPublished evt_alfa
  Cuando se vuelve a solicitar para usr_ana por el mismo evt_alfa
  Entonces no se crea una segunda
```

## Trazabilidad
- AGG-01 y AGG-02 son la versión que **funciona** de los dos diseños que fallan en la nota 15: `Video` como agregado grande con sus comentarios adentro (Vernon, *false invariants*) y el evento de dominio serializado tal cual.
- AGG-03 explicita que "solo se puntúa un video publicado" **no** es una invariante de `Rating`: cruza servicios y se resuelve con consistencia eventual (regla 4 de Vernon). Es riesgo aceptado, del mismo tipo que NOT-11.
- Los escenarios `@pendiente` (AGG-09, AGG-12) no se implementan hasta decidir el rango y el comportamiento del follow repetido.
- `Comment` no repite escenarios acá: sus invariantes ya están en COM-02, COM-05 y, con event sourcing, en ES-05 y ES-09.

## No cubierto
- Quién puede editar o borrar un comentario (autorización fuera de alcance).
- Test de arquitectura que vigile la regla de dependencias de `domain` (herramienta sin elegir).
