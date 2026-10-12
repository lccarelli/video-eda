# Comentarios

> **Estado:** borrador · **Contextos:** Interactions, Notifications · **ADR:** 0003, 0005 · **Notas:** 02, 03, 04

Key de `interactions.comment`: `commentId`. Los tres eventos (`CommentPosted`, `CommentEdited`, `CommentDeleted`) comparten topic y key.

> **Con event sourcing (ADR 0009 aceptado; nota 16):** `Comment` es el único agregado event-sourced. Su `sequence` es la **versión del stream** `commentId` en `comment_events`: `CommentPosted` es la versión 1 y cada cambio agrega una. Los IDs y los pasos de estos escenarios no cambian; lo que cambia es *cómo* se garantiza la secuencia (restricción única `(stream_id, version)`, ver `10-event-sourcing.md`). "Borrado" es **borrado lógico**: el texto permanece en el historial.

```gherkin
@unit
Escenario: COM-01 comentar un video publicado
  Dado que el video vid_paella está PUBLISHED
  Cuando el viewer usr_ana publica el comentario cmt_gracias
  Entonces se guarda cmt_gracias con sequence 1  # version 1 del stream (ES-03)
  Y se publica CommentPosted en interactions.comment con key cmt_gracias
```

```gherkin
@unit
Escenario: COM-02 editar y borrar incrementan la secuencia
  Dado que existe cmt_gracias con sequence 1
  Cuando usr_ana lo edita y luego lo borra
  Entonces se publican CommentEdited (sequence 2) y CommentDeleted (sequence 3)  # versiones 2 y 3 del stream (ES-05)
  Y los tres comparten la key cmt_gracias
```

```gherkin
@G1 @ADR-0003 @integracion
Escenario: COM-03 los eventos de un comentario se consumen en orden
  Dado que se publicaron CommentPosted, CommentEdited y CommentDeleted de cmt_gracias
  Cuando Notifications los consume
  Entonces los procesa en ese orden
  Y cmt_gracias queda borrado en su read model
```

```gherkin
@G1 @ADR-0003 @integracion
Escenario: COM-04 el delete llega antes que el post
  Dado que Notifications procesa CommentDeleted(cmt_gracias, sequence 3) antes que CommentPosted(cmt_gracias, sequence 1)
  Cuando llega después el CommentPosted tardío
  Entonces cmt_gracias no aparece en el read model
  Y el evento tardío se descarta por tener sequence menor al registrado
```

```gherkin
@unit
Escenario: COM-05 no se edita un comentario borrado
  Dado que cmt_gracias está borrado
  Cuando usr_ana intenta editarlo
  Entonces la operación se rechaza
  Y no se publica ningún evento
  # Con event sourcing: no se escribe ninguna fila en el stream (ES-09)
```

```gherkin
@G5 @ADR-0005 @integracion
Escenario: COM-06 comentario de un video aún desconocido
  Dado que Notifications recibe CommentPosted para vid_bonsai antes de conocer vid_bonsai
  Cuando reintenta de forma bloqueante hasta 3 veces con 500 ms de espera
  Entonces si vid_bonsai aparece, procesa el comentario en orden
  Y si no aparece, publica en interactions.dlq con causa, intentos y eventId
  Y emite una alerta
```

```gherkin
@G4 @ADR-0005 @integracion
Escenario: COM-07 CommentPosted duplicado
  Dado que CommentPosted (evt_beta, cmt_gracias) fue procesado
  Cuando llega otra vez con el mismo id evt_beta
  Entonces no se crea un segundo comentario
  Y no se genera una segunda notificación
```

```gherkin
@G5 @ADR-0005 @unit
Escenario: COM-08 evento con schema inválido va directo a DLQ
  Dado que llega un CommentPosted sin el campo videoId
  Cuando el consumer lo valida
  Entonces no se reintenta
  Y se publica en interactions.dlq con la causa y el eventId
  Y se emite una alerta
```

```gherkin
@integracion
Escenario: COM-09 comentario sobre un video borrado
  Dado que vid_paella fue borrado
  Cuando usr_ana intenta comentar vid_paella
  Entonces la operación se rechaza
```

## Trazabilidad
- COM-04 es el escenario Gherkin de la nota 02, ahora sobre la key `commentId`.
- COM-06 y COM-08 aplican la política de reintento bloqueante y DLQ de la nota 04.
- **Event sourcing (nota 16):** COM-01 y COM-02 se corresponden con ES-03 y ES-05; COM-05 con ES-09. COM-04 y COM-13 **siguen valiendo** del lado consumidor, porque los guards por `sequence` de Notifications no cambian (regla R10 de la nota 16).
- **Concurrencia entre ediciones:** el caso de dos ediciones simultáneas del mismo comentario no estaba cubierto antes; lo cubre ES-06.
