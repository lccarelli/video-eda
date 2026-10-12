# Contratos y garantías transversales

> **Estado:** borrador · **ADR:** 0002, 0003, 0006 · **Notas:** 02, 04, 10

```gherkin
@G6 @ADR-0006 @contrato
Escenario: CTR-01 un cambio incompatible bloquea el despliegue del productor
  Dado que Feed publicó un pact que exige creatorId en VideoPublished
  Cuando Videos propone un cambio que elimina creatorId
  Entonces la verificación del provider falla en el pipeline de Videos
  Y can-i-deploy responde "no" para Videos hacia production
  Y el cambio no se despliega
```

```gherkin
@G6 @ADR-0006 @contrato
Escenario: CTR-02 un campo opcional nuevo no rompe a nadie
  Dado que Videos agrega un campo opcional a VideoPublished
  Cuando se ejecuta la verificación de todos los pacts y la compatibilidad de schema
  Entonces ambos pasan
  Y can-i-deploy responde "yes"
```

```gherkin
@G6 @ADR-0006 @contrato
Escenario: CTR-03 una expectativa nueva de un consumer no frena al productor
  Dado que Notifications publica un pact con una expectativa que Videos aún no cumple
  Cuando Videos verifica sus pacts con pending pacts activado
  Entonces la verificación se publica como fallida
  Y el build de Videos no falla
  Y Notifications no puede desplegar esa versión hasta que Videos la verifique
```

```gherkin
@G5 @ADR-0005 @integracion
Escenario: CTR-04 un mensaje en DLQ siempre deja rastro
  Dado que un consumer envía un mensaje a su DLQ
  Cuando se inspecciona el mensaje
  Entonces conserva eventId, causa, intentos y topic de origen
  Y existe una alerta emitida
  Y reprocesarlo pasa por el inbox
```

```gherkin
@G7 @ADR-0003 @integracion
Escenario: CTR-05 cambiar la key de un topic falla un test
  Dado que interactions.comment usa la key commentId
  Cuando un cambio hace que el producer use videoId como key
  Entonces el test de orden por key falla
  # Vale igual para feed.follow (key followerId:creatorId): cambiarla rompe NOT-14.
  # Pact y la compatibilidad de schema no detectan este cambio (nota 10).
```

```gherkin
@G1 @ADR-0003 @unit
Escenario: CTR-06 el producer no admite configuraciones que rompan el orden
  Dado la configuración de producer de un servicio
  Cuando define partitioner.class o partitioner.ignore.keys
  Entonces el test de arranque falla
```

```gherkin
@G1 @ADR-0003 @unit
Escenario: CTR-07 la idempotencia del producer no se desactiva
  Dado la configuración de producer de un servicio
  Cuando establece enable.idempotence=false
  Entonces el test de arranque falla
```

```gherkin
@ADR-0001 @integracion
Escenario: CTR-08 el stack soporta los tres tipos de consumer
  Dado un broker Kafka 4.3.1 real
  Cuando se crean un consumer clásico, uno con group.protocol=consumer y un share consumer
  Entonces los tres arrancan sin error
  Y la versión de kafka-clients en el classpath es 4.3.x
```

```gherkin
@G6 @ADR-0002 @contrato
Escenario: CTR-09 un cambio incompatible se publica como type nuevo en el mismo topic
  Dado que Videos necesita renombrar videoId a id en VideoPublished
  Cuando publica VideoPublished.v2 en videos.video con la misma key
  Y sigue emitiendo VideoPublished (sin sufijo) durante la migración
  Entonces Feed (en VideoPublished) y Notifications (en VideoPublished.v2) procesan cada uno su tipo
  Y el orden por videoId se conserva entre ambos tipos
```

```gherkin
@G6 @ADR-0006 @contrato
Escenario: CTR-10 un tipo viejo no se retira mientras algún consumidor lo use
  Dado que Feed todavía tiene un pact sobre VideoPublished (sin sufijo)
  Cuando Videos propone dejar de emitir ese tipo
  Entonces can-i-deploy responde "no" para Videos
  Y se retira recién cuando Feed migra a v2
```

```gherkin
@ADR-0005 @unit
Escenario: CTR-11 un consumidor ignora los tipos que no conoce
  Dado que Interactions lee videos.video y recibe un evento con un type que no maneja
  Cuando lo procesa
  Entonces lo descarta sin error ni reintento
  Y confirma el offset
```

```gherkin
@G5 @ADR-0002 @integracion
Escenario: CTR-12 un consumidor que solo recibe tipos desconocidos genera alerta
  Dado que Feed solo entiende VideoPublished (sin sufijo)
  Y Videos dejó de emitir ese tipo por error
  Cuando Feed ignora tipos desconocidos durante el umbral definido
  Entonces el contador de eventos ignorados crece
  Y se emite una alerta
  Y el consumidor no queda "sano" por no recibir nada útil
```

## Trazabilidad
- CTR-01 es el escenario de la nota 10.
- CTR-05 existe porque Pact no cubre la key (receta oficial de Kafka de Pact).
- CTR-08 es la verificación obligatoria del ADR 0001. **Verificado el 2026-10-12** en `spikes/stack-check` (S1: Boot 4.1.1 + Spring Kafka 4.1.1 + `kafka-clients` 4.3.1 + broker 4.3.1); pasa a ser un test de regresión del pipeline.
- CTR-09 a CTR-11 salen de la nota 12 (versión en el `type`, no en el topic).
