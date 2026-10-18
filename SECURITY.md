# Política de seguridad

`video-eda` es un proyecto de ejemplo para estudiar arquitectura orientada a eventos, no un servicio en producción. Aun así se aplican las prácticas habituales de un repositorio público (ADR 0012): sin secretos versionados, dependencias y contenedores escaneados, privilegios mínimos y autenticación en el borde.

## Cómo reportar una vulnerabilidad
Usá **Private vulnerability reporting** de GitHub (pestaña *Security* → *Report a vulnerability*). No abras un issue público con detalles de la falla.

Incluí la versión o el commit, los pasos para reproducirla y el impacto que ves. Respondo en unos días; es un proyecto personal, así que no hay plazos garantizados.

## Qué está en alcance
- El código de `services/` y su configuración de contenedores (`compose.yaml`, `Dockerfile`).
- Los workflows de `.github/` y los scripts de `deploy/`.

## Qué no
- Las credenciales de `compose.yaml` y de los tests son de desarrollo local y no protegen datos reales.
- Ataques que requieren acceso previo al host de Docker.

## Secretos
Nunca se versionan secretos reales. El repositorio corre **gitleaks** como hook de pre-commit y en CI. Si encontrás un secreto expuesto, reportalo por el mismo canal privado.
