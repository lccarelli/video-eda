# CQRS y read models

> **Estado:** borrador para revisión · **Última actualización:** 2026-10-12 · **Contextos:** Videos, Interactions, Feed · **Notas:** 14, 03, 05 · **ADR:** 0003, 0005, 0009 (aceptado)

Criterios de aceptación del lado query y de la reproyección. Nivel de CQRS (nota 14): **nivel 1** dentro de cada servicio (modelos separados, misma base) y **nivel 2** entre contextos (proyección por eventos). Sin bus de comandos ni segunda base por servicio.

> **Dependencia de configuración:** QRY-02 a QRY-04 reconstruyen desde el topic, lo que exige `retention.ms=-1` en `videos.video` (decisión aprobada por Laura, 2026-10-12; falta recogerla en el ADR 0003 y la nota 13). Con la retención por defecto de Kafka 4.3 (7 días) esos escenarios no son posibles.

## Orden ZOMBIES

| Letra | Escenarios |
|---|---|
| Zero | QRY-02 |
| One | QRY-01, QRY-03, QRY-06 |
| Many | QRY-04 |
| Interface / Simple | QRY-05 |

```gherkin
@one @unit
Escenario: QRY-01 el comando devuelve lo creado
  Dado que usr_ana publica el comentario cmt_gracias en vid_paella
  Cuando la API responde 201
  Entonces el cuerpo trae id cmt_gracias, sequence 1 y el texto
  Y la UI lo muestra sin consultar la lista
  Y la lista de vid_paella incluye cmt_gracias en cuanto se aplica la proyección
```

```gherkin
@zero @G8 @integracion
Escenario: QRY-02 reconstruir con el topic vacío
  Dado que feed_entry tiene filas y videos.video no tiene eventos retenidos
  Cuando se vacía el read model y se reproyecta desde el offset inicial
  Entonces feed_entry queda vacío y no hay error
```

```gherkin
@one @G8 @integracion
Escenario: QRY-03 reconstruir con un evento
  Dado que videos.video tiene un VideoPublished de vid_paella (usr_lucia) y usr_ana sigue a usr_lucia
  Cuando se vacía el read model y se reproyecta
  Entonces existe exactamente una entrada (usr_ana, vid_paella)
```

```gherkin
@many @G4 @G8 @integracion
Escenario: QRY-04 reconstruir con muchos eventos, duplicados y reentregas
  Dado que videos.video tiene 1.000 eventos
  Y 10 de ellos están duplicados
  Y 5 llegan con una sequence menor a la ya aplicada
  Cuando se vacía el read model y se reproyecta
  Entonces feed_entry es idéntico al que existía antes
  Y reproyectar una segunda vez no cambia nada
```

```gherkin
@unit
Escenario: QRY-05 una consulta no modifica estado
  Dado que existe el comentario cmt_gracias
  Cuando se ejecuta GET /videos/vid_paella/comments cinco veces
  Entonces no se escribió ninguna fila en el event store, en el outbox ni en ninguna tabla de dominio
```

```gherkin
@one @integracion
Escenario: QRY-06 el feed responde con Videos caído
  Dado que Feed procesó VideoPublished de vid_paella de usr_lucia y usr_ana sigue a usr_lucia
  Y Videos no responde
  Cuando usr_ana abre su feed
  Entonces ve vid_paella desde feed_entry
  Y no hay ninguna llamada a Videos
```

## El caso que falla (diseño descartado)

Dos diseños que estos escenarios existen para impedir; ninguno es escenario a implementar:

- **Feed armado por consulta síncrona:** con Videos caído, el feed completo falla aunque Feed esté sano. Lo cubre QRY-06.
- **La UI no ve su propio comentario:** si la UI reemplaza lo que mostraba por una lista que aún no tiene `cmt_gracias`, la persona cree que se perdió. Lo cubre QRY-01.

## Trazabilidad
- **Reglas de la nota 14:** regla 3 (los `GET` no escriben) → QRY-05; regla 5 (el comando devuelve el recurso con su `sequence`) → QRY-01; regla 6 (todo read model es reconstruible) → QRY-02 a QRY-04.
- QRY-04 reutiliza el inbox y el guard por `sequence` de INB-02 e INB-03 (`07-zombies.md`) en un contexto de reproyección.
- La reproyección del read model de comentarios (event store propio, sin pasar por Kafka) es **ES-07** (`10-event-sourcing.md`).
- `minSequence` (lectura "al menos tan nueva como mi escritura") **no está incluido**: es inferencia de la nota 14 y queda como extensión.
- La validación del lado command contra un read model local desactualizado (semantic lock) se cubre en RAT-02, RAT-07 y AGG-03.

## No cubierto
- Latencia máxima de la proyección: sin cota fija, a medir (`../07-nfr/garantias.md`).
- Reproyección con el costo de disco de `retention.ms=-1`: no medido.
