# Resiliencia, volumen y reprocesamiento

> **Estado:** borrador para revisión · **Última actualización:** 2026-10-16 · **Notas:** 19, 03, 04, 14 · **ADR:** 0011 (aceptado el 2026-10-17)

Criterios de aceptación de lo que pasa cuando algo falla, cuando hay mucho volumen y cuando hay que reprocesar sin romper el orden por agregado. Los escenarios con `@pendiente` dependen de una decisión sin tomar.

**Verificados en el spike S5 (2026-10-16, clientes planos):** RES-01, RES-02, RES-03, RES-04, RES-05 y RES-07 (`spikes/resilience-check`). Siguen sin probar RES-06 (ya cubierto en parte por S4), RES-08 y RES-09. Todavía no hay tests del servicio.

## Escenarios

```gherkin
@G1 @G5 @ADR-0011 @integracion @zero
Escenario: RES-01 un hueco de sequence aparta el evento en vez de perderlo
  Dado que evt_alfa (CommentPosted, sequence 1) de cmt_gracias está en interactions.dlq
  Cuando llega evt_beta (CommentEdited, sequence 2) de cmt_gracias
  Entonces no se aplica
  Y se publica en interactions.dlq con causa SEQUENCE_GAP, la key cmt_gracias y el eventId
  Y no queda registrado como procesado en el inbox

@G1 @G4 @ADR-0011 @integracion @one
Escenario: RES-02 el redrive reaplica en orden por el mismo handler
  Dado que evt_alfa (sequence 1) y evt_beta (sequence 2) de cmt_gracias están en interactions.dlq
  Cuando se ejecuta el redrive
  Entonces se aplica evt_alfa y después evt_beta
  Y el comentario queda con el texto de evt_beta
  Y la DLQ queda sin pendientes de cmt_gracias

@G4 @ADR-0011 @integracion @one
Escenario: RES-03 un evento ya aplicado que reaparece en el redrive se descarta
  Dado que evt_alfa ya fue aplicado
  Cuando el redrive vuelve a presentar evt_alfa
  Entonces el inbox lo descarta y no se repite ningún efecto

@G5 @ADR-0011 @integracion @zero
Escenario: RES-04 un mensaje venenoso no frena a las demás keys
  Dado que llega un CommentPosted con schema inválido para cmt_gracias
  Y llega un CommentPosted válido para otro comentario en la misma partición
  Cuando los procesa el consumer
  Entonces el inválido va directo a interactions.dlq sin reintentos
  Y el válido se aplica

@G5 @ADR-0011 @integracion @one
Escenario: RES-05 el proveedor de envío caído pausa el consumer sin llenar la DLQ
  Dado que el proveedor de notificaciones no responde
  Cuando Notifications procesa VideoPublished de vid_paella y agota el reintento acotado
  Entonces el container se pausa y ningún mensaje va a notifications.dlq
  Y cuando el proveedor vuelve se retoma desde el mismo offset
  Y se envía una sola notificación por evento

@G2 @G3 @ADR-0004 @integracion @one
Escenario: RES-06 Kafka caído: el outbox acumula y publica en orden al volver
  Dado que Kafka no está disponible
  Cuando usr_lucia publica vid_paella y después la borra
  Entonces las filas del outbox quedan sin published_at
  Y al volver Kafka se publican VideoPublished y VideoDeleted en ese orden con la key vid_paella

@G1 @G4 @ADR-0011 @integracion @many
Escenario: RES-07 un pico de 50 comentarios no pierde ni desordena
  Dado que usr_viewer-01 a usr_viewer-50 comentan vid_paella a la vez
  Cuando el consumer procesa con retraso
  Entonces el lag llega a cero
  Y por cada key los eventos se aplican en orden de sequence
  Y no hay mensajes en interactions.dlq

@G8 @ADR-0011 @integracion @many @pendiente
Escenario: RES-08 replay desde el principio reconstruye el read model idéntico
  Dado un read model de Feed y su inbox con datos
  Cuando se detiene el grupo, se vacían read model e inbox y se resetean los offsets a earliest
  Entonces al reanudar el read model queda idéntico al anterior
  # @pendiente: Notifications no se reproyecta (efectos externos); falta definir qué grupos permiten replay.

@ADR-0011 @unit @one
Escenario: RES-09 el tope de concurrencia hacia el proveedor se respeta
  Dado un límite de concurrencia de 10 llamadas al proveedor de envío
  Cuando 50 eventos llegan a la vez
  Entonces nunca hay más de 10 llamadas simultáneas
  Y los 50 se procesan
