# Ids de ejemplo

> **Estado:** vigente · **Última actualización:** 2026-10-12 · **Decisión:** nota 17 (aprobada por Laura el 2026-10-12)

Los escenarios y las notas usan **alias legibles** (`<prefijo>_<nombre>`). Cada alias corresponde a **un único id real** con el formato de la nota 17: `<prefijo>_<UUIDv7>`. Los ejemplos de payload, los AsyncAPI y los JSON Schema usan siempre el id real; las prosas, los diagramas y los Gherkin usan el alias.

## Reglas

1. Un alias denota **siempre** la misma entidad en todo el repositorio. No se reutiliza para otra cosa.
2. Los alias nuevos se agregan **en este archivo** antes de usarlos.
3. Formato real: `usr_`, `vid_`, `cmt_`, `ntf_`, `evt_`, `cor_` + UUIDv7 en minúsculas con guiones. La `Idempotency-Key` es un UUID plano, sin prefijo.
4. Ids compuestos: `{aliasA}:{aliasB}` y `{idA}:{idB}`; el `:` no pertenece al alfabeto del id.
5. El ejemplo del **caso que falla** de la nota 17 (`c-4521`, secuencia reiniciada) conserva el id corto a propósito.

## Tabla

| Alias | Prefijo | Qué representa | Antes | Id real |
|---|---|---|---|---|
| `usr_ana` | `usr` | Viewer que puntúa y comenta | `u-42` | `usr_01a13161-112a-7681-b280-02ac0205cd15` |
| `usr_bruno` | `usr` | Viewer | `u-43` | `usr_01a13161-114f-70ce-be25-c49f005d3def` |
| `usr_carlos` | `usr` | Viewer que empieza a seguir después de una publicación | `u-44` | `usr_01a13161-1174-70b0-b447-58978fd7fa69` |
| `usr_diego` | `usr` | Viewer que comenta al mismo tiempo que Ana | `u-77` | `usr_01a13161-1199-7c2b-aacd-952d87f637f7` |
| `usr_lucia` | `usr` | Creator que publica videos | `c-7` | `usr_01a13161-11be-7119-8e3e-bf07f13eb833` |
| `usr_mateo` | `usr` | Creator sin seguidores | `c-9` | `usr_01a13161-11e3-764d-8aee-4eb50cdb9329` |
| `vid_paella` | `vid` | Video de ejemplo principal | `v-1` | `vid_01a13161-1208-73ad-b8df-5e1e8e69b948` |
| `vid_surf` | `vid` | Video que sigue en PROCESSING | `v-2` | `vid_01a13161-122d-7e19-8ee9-61e08f4cc5fd` |
| `vid_bonsai` | `vid` | Video aún desconocido para Notifications | `v-9` | `vid_01a13161-1252-7131-a2fc-21ea9b78edcb` |
| `vid_ramen` | `vid` | Video de los escenarios de la saga de publicación | `9f3a` | `vid_01a13161-1277-7e7b-9435-b109a7e95989` |
| `cmt_gracias` | `cmt` | Comentario principal ("Muy bueno, gracias") | `c-1, c1` | `cmt_01a13161-129c-76ff-af15-e5821d573d09` |
| `cmt_buenisimo` | `cmt` | Segundo comentario | `c-2` | `cmt_01a13161-12c1-78a2-8552-de9020b53d23` |
| `evt_alfa` | `evt` | Primer evento de ejemplo | `e1` | `evt_01a13161-12e6-7e09-b67f-10ca57c43328` |
| `evt_beta` | `evt` | Segundo evento de ejemplo | `e5` | `evt_01a13161-130b-746b-a4cf-411498498e7c` |
| `evt_gamma` | `evt` | Tercer evento de ejemplo | `e7` | `evt_01a13161-1330-7b55-9cdc-2b8ca7329fec` |
| `evt_delta` | `evt` | Cuarto evento de ejemplo | `e2, e3, e4, e6` | `evt_01a13161-1355-79ea-92a1-85411003d528` |
| `evt_epsilon` | `evt` | VideoDeleted de ejemplo (AsyncAPI) | `—` | `evt_01a13161-1864-73ab-9c84-a5cd7222d728` |
| `evt_zeta` | `evt` | ProcessVideo de ejemplo (AsyncAPI) | `—` | `evt_01a13161-1889-7fde-a4d5-22e45e5c7474` |
| `evt_eta` | `evt` | CommentEdited de ejemplo (AsyncAPI) | `—` | `evt_01a13161-18ae-7451-9583-60a5d4d49510` |
| `evt_theta` | `evt` | CommentDeleted de ejemplo (AsyncAPI) | `—` | `evt_01a13161-18d3-789a-828e-fd36c097ce13` |
| `evt_iota` | `evt` | CreatorUnfollowed de ejemplo (AsyncAPI) | `—` | `evt_01a13161-18f8-7ec3-ba62-5b9d7218d06e` |
| `ntf_alfa` | `ntf` | Notificación de una publicación | `n-1` | `ntf_01a13161-137a-7b3d-8612-7637bb8bebf1` |
| `ntf_beta` | `ntf` | Notificación originada en un comentario | `n-2` | `ntf_01a13161-139f-7168-b522-41a76411f939` |
| `cor_publicacion` | `cor` | Correlación de un flujo de publicación | `c-7731 (nota 01)` | `cor_01a13161-13c4-7752-b78c-4c2b8c8039fb` |
| `cmt_inexistente` | `cmt` | Comentario cuyo stream aún no tiene eventos | `c-9` (solo en `10-event-sourcing.md`) | `cmt_01a13161-13e9-752e-89a7-834df2a74de4` |
| `cor_comentario` | `cor` | Correlación de un flujo de comentario (publicar o editar) | `req-8812` (nota 16) | `cor_01a13161-596e-7cc9-94db-a57740b2d002` |

## Idempotency-Key (UUID plano)

| Alias | Qué representa | Antes | Valor |
|---|---|---|---|
| `idk_subida` | UploadVideo del creator | `k-1` | `01a13161-1f9e-7f3a-8498-6164563648ad` |
| `idk_puntuacion` | POST de puntuación sin respuesta | `k-9` | `01a13161-1fc3-74ad-822b-54a19cd3ef8c` |

## Rangos y casos especiales

- **Muchos viewers** (RAT-10 y similares): `usr_viewer-01` a `usr_viewer-50`; el id real de cada uno se genera en el test, no se fija acá.
- **Worker** (`w-1`): `worker-transcode-1`. No es una entidad de dominio sino el nombre de la instancia en el share group; no lleva prefijo ni UUID.
- **Versiones del stream** (`v2`, `v3` en la nota 16) no son ids: se escriben "versión 2", "versión 3". El sufijo `.v2` del `type` tampoco es un id y no se toca.
- **Claves compuestas:** `vid_paella:usr_ana` (puntuación), `usr_ana:usr_lucia` (follow, orden `followerId:creatorId`).

## Historial

| Fecha | Cambio |
|---|---|
| 2026-10-12 | Creación a partir de la nota 17; reemplaza los ids cortos de escenarios y notas |
