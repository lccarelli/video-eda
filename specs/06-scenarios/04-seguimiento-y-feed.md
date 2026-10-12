# Seguimiento y feed

> **Estado:** borrador · **Contexto:** Feed · **ADR:** 0003, 0005 · **Notas:** 02, 03, 07

Seguimientos y entradas son tablas de Feed. **Los follows se publican** en `feed.follow` porque Notifications los consume (opción a, Laura, 2026-10-12; `03-events/README.md`); las entradas del feed no (no hay `feed.entry`). Clave del seguimiento y key del topic: `followerId:creatorId`, con columna `sequence`. Clave de la entrada: `(followerId, videoId)`. Feed es dueño de los seguimientos (hipótesis a revisar, `01-domain/event-storming.md`).

> **Corrección 2026-10-12 (dos pasos):** primero, la regla de publicación quitó los topics de Feed y reescribió FEED-01 a FEED-04 y FEED-06 como estado local. Después, la opción (a) devolvió `feed.follow`: FEED-01, FEED-03, FEED-04 y FEED-07 vuelven a publicar, conservando el ID. La concurrencia sobre la fila sigue siendo de Feed; el orden por key vuelve a importar para el consumidor (NOT-14, NOT-15).

```gherkin
@unit
Escenario: FEED-01 seguir a un creator
  Dado que el viewer usr_ana no sigue a usr_lucia
  Cuando usr_ana sigue a usr_lucia
  Entonces se guarda el seguimiento (usr_ana, usr_lucia) con sequence 1
  Y se registra CreatorFollowed en el outbox en la misma transacción
  Y se publica en feed.follow con key usr_ana:usr_lucia y sequence 1
  # Antes (primer paso 2026-10-12): "no se publica ningún evento". Ahora Notifications consume los follows.
```

```gherkin
@integracion
Escenario: FEED-02 una publicación aparece en el feed de los seguidores
  Dado que usr_ana y usr_bruno siguen a usr_lucia
  Cuando se publica VideoPublished de un video de usr_lucia
  Entonces el feed de usr_ana y el de usr_bruno contienen la entrada
  Y no se publica ningún evento: el feed es el read model
```

```gherkin
@concurrencia @integracion
Escenario: FEED-03 follow y unfollow del mismo par se aplican en orden
  Dado que usr_ana hace follow, unfollow y follow de usr_lucia en ese orden
  Cuando Feed aplica los tres comandos sobre la fila (usr_ana, usr_lucia)
  Entonces el estado final es "sigue" con sequence 3
  Y una petición tardía que leyó un sequence anterior se rechaza o reintenta, sin revertirlo
  Y se publican en feed.follow, en ese orden y con la misma key usr_ana:usr_lucia, CreatorFollowed(1), CreatorUnfollowed(2) y CreatorFollowed(3)
  # Antes (primer paso): solo la fila con su sequence. El orden de los eventos publicados lo da la key; su consumo, NOT-14.
```

```gherkin
@integracion
Escenario: FEED-04 follow repetido
  Dado que usr_ana ya sigue a usr_lucia
  Cuando usr_ana envía otra vez FollowCreator(usr_lucia)
  Entonces existe un solo seguimiento
  Y no se vuelve a traer el backfill de FEED-06
  Y no se publica un segundo CreatorFollowed
  # La unicidad del seguimiento la da la PK (followerId, creatorId). El duplicado en el consumidor es NOT-15.
```

```gherkin
@integracion
Escenario: FEED-05 un video borrado desaparece del feed
  Dado que el feed de usr_ana contiene el video vid_paella
  Cuando Feed procesa VideoDeleted de vid_paella
  Entonces la entrada ya no se muestra
```

```gherkin
@integracion
Escenario: FEED-06 seguir a un creator trae sus publicaciones anteriores
  Dado que usr_lucia tiene 5 videos PUBLISHED anteriores al follow
  Cuando usr_ana empieza a seguir a usr_lucia
  Entonces el feed de usr_ana incluye las N publicaciones más recientes de usr_lucia  # N configurable, valor sin definir
  Y cada entrada aparece una sola vez aunque el comando de follow se repita
  # Decisión de Laura (2026-10-12): sí trae las anteriores. Cuántas (N) y si son todas, a definir.
```

```gherkin
@zero @integracion
Escenario: FEED-12 seguir a un creator sin publicaciones previas
  Dado que usr_mateo no tiene videos PUBLISHED
  Cuando usr_ana empieza a seguir a usr_mateo
  Entonces el feed de usr_ana no cambia
  Y el seguimiento queda registrado
```

```gherkin
@integracion
Escenario: FEED-13 un video borrado no se trae al seguir
  Dado que usr_lucia tuvo un video vid_paella que fue borrado (DELETED)
  Cuando usr_ana empieza a seguir a usr_lucia
  Entonces vid_paella no aparece en el feed de usr_ana
```

```gherkin
@integracion
Escenario: FEED-07 dejar de seguir quita las entradas de ese creator
  Dado que usr_ana tiene 3 entradas de usr_lucia en su feed
  Cuando usr_ana deja de seguir a usr_lucia
  Entonces esas 3 entradas dejan de mostrarse
  Y las publicaciones futuras de usr_lucia no llegan al feed
  Y se publica CreatorUnfollowed en feed.follow con key usr_ana:usr_lucia y el sequence siguiente
```

```gherkin
@G2 @ADR-0004 @integracion
Escenario: FEED-14 el follow no se pierde si el servicio cae tras el commit
  Dado que usr_ana sigue a usr_lucia y la transacción se confirmó con su fila de outbox
  Cuando el servicio Feed cae antes de publicar y se reinicia
  Entonces CreatorFollowed(usr_ana:usr_lucia) se publica en feed.follow
  Y no se pierde ni se publica un evento sin que exista el seguimiento
```

## Trazabilidad
- FEED-03 era el caso `CreatorFollowed` y `CreatorUnfollowed` de la nota 02 (orden por key). Dentro de Feed es concurrencia optimista sobre la fila (`sequence` como versión); hacia afuera el orden lo da la key `followerId:creatorId` en `feed.follow`, y quien lo necesita es Notifications (NOT-14).
- FEED-14 prueba G2 para `Follow`, que usa outbox como `Video` (ADR 0004, ADR 0009).
- FEED-06 exige que Feed conserve un read model de videos publicados por creator (alimentado por `videos.video`, el único topic de Videos que Feed consume), para armar el backfill sin llamadas síncronas a Videos. Requiere que `VideoPublished` y `VideoDeleted` se apliquen en orden por `videoId`.
- FEED-07 es simétrico de FEED-06 y es decisión de diseño, no de fuente.
- FEED-06: el backfill se dispara ahora en la misma transacción local del follow, no por un evento propio de Feed.
