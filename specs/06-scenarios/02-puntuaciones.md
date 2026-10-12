# Puntuaciones

> **Estado:** borrador · **Contexto:** Interactions · **ADR:** 0003, 0005 · **Notas:** 02, 03, 08

Sin topic: las puntuaciones no se publican (nadie fuera de Interactions las consume; regla de publicación, `03-events/README.md`). La clave natural es `videoId:raterId`, PK de la tabla. Se guarda **estado final** por par, no deltas, con una columna `sequence` por fila.

> **Corrección 2026-10-12:** RAT-01, RAT-03 y RAT-04 asumían un topic `interactions.rating`. Se reescribieron conservando el ID; el orden ya no depende de Kafka.

```gherkin
@unit
Escenario: RAT-01 puntuar un video publicado
  Dado que el video vid_paella está PUBLISHED
  Cuando el viewer usr_ana lo puntúa con 5 estrellas
  Entonces se guarda la puntuación (usr_ana, vid_paella) = 5 con sequence 1
  Y no se publica ningún evento (VideoRated es interno de Interactions)
```

```gherkin
@unit @ADR-0005
Escenario: RAT-02 no se puede puntuar un video que no está publicado
  Dado que el video vid_surf está en PROCESSING
  Cuando el viewer usr_ana intenta puntuarlo
  Entonces la operación se rechaza
  Y no se publica ningún evento
```

```gherkin
@integracion
Escenario: RAT-03 el mismo comando repetido no altera el promedio
  Dado que el promedio de vid_paella es 4.0 con 10 puntuaciones
  Cuando usr_ana envía dos veces RateVideo(vid_paella, 5 estrellas) sin Idempotency-Key
  Entonces vid_paella tiene 11 puntuaciones
  Y existe una sola fila (usr_ana, vid_paella), porque la escritura es un upsert por PK
  # Antes: reentrega de VideoRated con inbox (G4). Sin consumo no hay inbox; la idempotencia sale de la PK.
```

```gherkin
@concurrencia @integracion
Escenario: RAT-04 dos puntuaciones concurrentes del mismo par
  Dado que usr_ana puntuó vid_paella con 4 (fila con sequence 1)
  Cuando llegan a la vez dos peticiones, una con 2 y otra con 5, ambas leyendo sequence 1
  Entonces una se aplica y deja sequence 2
  Y la otra se rechaza o reintenta por conflicto de versión, sin pisar la anterior
  Y la puntuación vigente es la de la petición que ganó
  # Antes: eventos de Kafka llegando desordenados con guard por sequence (G1). Ahora es optimistic locking sobre la fila.
```

```gherkin
@unit
Escenario: RAT-05 puntuar dos veces reemplaza, no suma
  Dado que usr_ana ya puntuó vid_paella con 3
  Cuando usr_ana puntúa vid_paella con 5
  Entonces vid_paella sigue teniendo una puntuación de usr_ana, con valor 5
  Y el promedio se recalcula sin contar el 3
```

```gherkin
@unit @ADR-0005
Escenario: RAT-06 doble toque por mala conexión
  Dado que usr_ana envió POST /videos/vid_paella/ratings con Idempotency-Key idk_puntuacion y no recibió respuesta
  Cuando reintenta con idk_puntuacion
  Entonces recibe la respuesta original
  Y se registra una sola puntuación
```

```gherkin
@integracion
Escenario: RAT-07 puntuación de un video borrado
  Dado que el video vid_paella fue borrado (VideoDeleted procesado por Interactions)
  Cuando llega una puntuación para vid_paella
  Entonces se rechaza
  Y las puntuaciones existentes de vid_paella dejan de contarse
```

## Trazabilidad
- RAT-02 y RAT-07 usan el read model local de videos publicados (semantic lock, nota 08).
- RAT-03 ya no es el caso de inbox de la nota 03 (no hay consumo): la idempotencia sale de la PK. El inbox sigue aplicándose a lo que Interactions **consume** (`videos.video`).
- RAT-04 usa `sequence` como versión de la fila (concurrencia optimista), no como guard de orden de Kafka (nota 02). Tag `@G1` retirado: G1 es orden por key en Kafka.
