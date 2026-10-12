# Notificaciones

> **Estado:** borrador · **Contexto:** Notifications · **ADR:** 0005, 0008 · **Notas:** 03, 04

Modelo (ADR 0005, actualizado por el ADR 0008): **un solo consumer group** con orden por key sobre `videos.video`, `interactions.comment` y `feed.follow`. Crea, cancela y envía notificaciones (`REQUESTED`, `SENT`, `FAILED`, `CANCELLED`) en su tabla propia; el envío es una llamada simulada. No publica eventos. Mantiene su propia lista de seguidores por creator como read model, construida desde `feed.follow` (opción a, Laura, 2026-10-12); no consulta a Feed.

```gherkin
@integracion
Escenario: NOT-01 notificar a los seguidores de una publicación
  Dado que Notifications procesó CreatorFollowed de usr_ana y de usr_bruno sobre usr_lucia
  Cuando se publica VideoPublished de un video de usr_lucia
  Entonces se solicita una notificación para usr_ana y otra para usr_bruno
  # Antes: "usr_ana y usr_bruno siguen a usr_lucia" sin decir de dónde lo sabía Notifications (abierto resuelto por la opción a).
```

```gherkin
@G4 @ADR-0005 @integracion
Escenario: NOT-02 evento duplicado no genera una segunda notificación
  Dado que VideoPublished (evt_alfa) ya generó la notificación ntf_alfa para usr_ana
  Cuando evt_alfa llega otra vez
  Entonces no se crea otra notificación para usr_ana
```

```gherkin
@G5 @ADR-0005 @integracion
Escenario: NOT-03 proveedor de envío caído
  Dado que el proveedor de envío falla de forma transitoria
  Cuando el consumer reintenta de forma bloqueante con backoff y jitter
  Entonces el envío se reintenta hasta el tope de intentos
  Y si se agota, publica en notifications.dlq con causa y eventId
  Y emite una alerta
```

```gherkin
@integracion
Escenario: NOT-04 un comentario notifica al creator del video
  Dado que el video vid_paella pertenece al creator usr_lucia
  Cuando se publica CommentPosted sobre vid_paella
  Entonces se solicita una notificación para usr_lucia
  Y no se notifica al autor del comentario
```

```gherkin
@integracion
Escenario: NOT-05 notificaciones pendientes de un video borrado se cancelan, no se envían
  Dado que hay 3 notificaciones en REQUESTED sobre vid_paella
  Cuando Notifications procesa VideoDeleted de vid_paella
  Entonces las 3 pasan a CANCELLED
  Y ninguna se envía
  Y los registros se conservan (no se borran)
```

```gherkin
@integracion
Escenario: NOT-10 las notificaciones ya enviadas no se deshacen
  Dado que la notificación ntf_alfa sobre vid_paella ya está en SENT
  Cuando Notifications procesa VideoDeleted de vid_paella
  Entonces ntf_alfa sigue en SENT
```

```gherkin
@experimento
Escenario: NOT-11 el borrado llega mientras se envía (riesgo aceptado)
  Dado que Notifications tiene una notificación ntf_beta sobre vid_paella originada en un comentario (interactions.comment)
  Cuando se procesa VideoDeleted de vid_paella (videos.video) casi al mismo tiempo
  Entonces ntf_beta puede enviarse igual
  # Los dos eventos viajan por topics distintos y entre topics no hay orden.
  # Las notificaciones originadas en videos.video no tienen esta carrera: misma key, misma partición.
  # Se acepta como mejor esfuerzo; se mide cuántas veces ocurre.
```

```gherkin
@integracion @G1
Escenario: NOT-12 no se crean notificaciones de un video ya borrado
  Dado que Notifications procesó VideoDeleted de vid_paella
  Cuando llega un VideoPublished tardío de vid_paella con sequence menor
  Entonces no se crea ninguna notificación para vid_paella
```

```gherkin
@experimento
Escenario: NOT-06 caída entre el envío y la confirmación (riesgo aceptado)
  Dado que Notifications envió la notificación ntf_alfa y cae antes de confirmar el offset
  Cuando el evento se reentrega
  Entonces la base registra un único envío por notificationId
  Pero el proveedor puede recibir dos pedidos
  # Sin clave de idempotencia ante el proveedor no hay garantía de envío único.
  # Estado: riesgo conocido, a verificar con el proveedor elegido (nota 03).
```

```gherkin
@one @integracion
Escenario: NOT-13 la lista de seguidores se construye desde feed.follow
  Dado que Notifications no conoce seguidores de usr_lucia
  Cuando procesa CreatorFollowed(usr_ana, usr_lucia, sequence 1)
  Entonces usr_ana figura como seguidor de usr_lucia en el read model de Notifications
  Y no se solicita ninguna notificación por el follow en sí
```

```gherkin
@G1 @ADR-0003 @integracion
Escenario: NOT-14 follow y unfollow llegan reordenados
  Dado que Notifications procesa CreatorUnfollowed(usr_ana, usr_lucia, sequence 2) antes que CreatorFollowed(usr_ana, usr_lucia, sequence 1)
  Cuando llega después el CreatorFollowed tardío
  Entonces usr_ana no figura como seguidor de usr_lucia
  Y el evento tardío se descarta por tener sequence menor al registrado
  # Con la misma key followerId:creatorId llegan en orden por partición; esto cubre reintentos y reentregas.
```

```gherkin
@G4 @ADR-0005 @integracion
Escenario: NOT-15 CreatorFollowed duplicado
  Dado que CreatorFollowed (evt_gamma, usr_ana, usr_lucia) fue procesado
  Cuando llega otra vez con el mismo id evt_gamma
  Entonces usr_ana figura una sola vez como seguidor de usr_lucia
  Y una publicación posterior genera una sola notificación para usr_ana
```

```gherkin
@integracion
Escenario: NOT-16 un seguidor posterior a la publicación no la recibe
  Dado que usr_lucia publicó el video vid_paella y usr_carlos empezó a seguirlo después
  Cuando Notifications procesa CreatorFollowed de usr_carlos
  Entonces no se solicita ninguna notificación sobre vid_paella para usr_carlos
  # La notificación nace de la publicación, no del seguimiento. El backfill es del feed (FEED-06), no de Notifications.
```

```gherkin
@integracion
Escenario: NOT-17 un seguidor que dejó de seguir antes de la publicación no la recibe
  Dado que usr_ana siguió y dejó de seguir a usr_lucia (sequence 2 procesado)
  Cuando se publica VideoPublished de un video de usr_lucia
  Entonces no se solicita ninguna notificación para usr_ana
```

## Trazabilidad
- NOT-06 documenta el límite del inbox con efectos externos (nota 03): no es una garantía sino un riesgo declarado.
- NOT-03 aplica las reglas 2 a 5 del ADR 0005.
- **Resuelto (opción a, Laura, 2026-10-12):** NOT-01 y NOT-08 se apoyan en la lista de seguidores que Notifications mantiene desde `feed.follow`. NOT-13 a NOT-17 cubren esa proyección en orden ZOMBIES: uno (NOT-13), muchos y reordenado (NOT-14, NOT-15), y bordes de tiempo (NOT-16, NOT-17). El caso cero es NOT-07 (`07-zombies.md`).
- NOT-14 es el escenario de la nota 02 (`CreatorFollowed` / `CreatorUnfollowed` por key) ahora del lado del consumidor.
- Notifications tiene tres topics de entrada; entre ellos no hay orden: un `CreatorFollowed` puede llegar después de un `VideoPublished` publicado antes por Videos. NOT-16 fija que eso no genera retroactivos.
