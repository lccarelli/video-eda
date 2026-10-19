# ADR 0012: Calidad y seguridad desde el inicio

> **Estado:** **Aceptado** (Laura, 2026-10-17: pidió sumar Grafana + Loki, SonarQube, lint y seguridad "todo en conjunto, no al final", y eligió las cuatro decisiones del plan) · **Última actualización:** 2026-10-17 · **Enmienda** la línea del `docs/metodo-de-trabajo.md` que dejaba la seguridad fuera de alcance (ADR 0008 no la mencionaba).

## Contexto
El repositorio es público y pretende ser evidencia demostrable. Hasta hoy se aplicaba "la seguridad está fuera de alcance, salvo no subir credenciales reales", y no había análisis estático, pipeline ni escaneo de dependencias. Laura pidió trabajar de forma ágil: calidad y seguridad avanzan junto con las funcionalidades, en fases finas que terminan verificadas, no como una fase final.

## Decisión
1. **Fases, en este orden, cada una con su parte de seguridad y su verificación:**

| # | Fase | Entrega |
|---|---|---|
| 0 | Base del repo | Repo git propio, `.gitignore`, `.editorconfig`, MIT, `SECURITY.md`, `CODEOWNERS`, **gitleaks** (hook y CI) |
| 1 | Lint y formato | **ktlint** y **detekt** dentro de `./gradlew check`; **hadolint**, **shellcheck**, **actionlint** |
| 2 | Observabilidad | **Loki + Alloy + Grafana** en `compose.yaml` (cierra las hipótesis del ADR 0010) |
| 3 | CI | GitHub Actions: build y tests, JaCoCo, **Trivy**, **CodeQL**, dependency-review, **Dependabot**, SBOM; acciones fijadas por SHA y `permissions` mínimos |
| 4 | SonarQube | **SonarQube Community** en el perfil `quality` de Compose + plugin Gradle; paso opcional de **SonarCloud** en CI |
| 5 | Seguridad de la API | **Keycloak** (OIDC) + Spring Security como resource server con JWT; `creatorId` sale del token; autorización por dueño; cabeceras de seguridad |
| 6 | Endurecimiento | Imágenes por digest, `read_only`, `cap_drop`, `no-new-privileges`, límites, secretos fuera de `compose.yaml`, usuario de base por servicio |

2. **Solo Docker como requisito** (ADR 0007): todas las herramientas corren desde imágenes de contenedor, tanto en local como en CI; ninguna se instala en la máquina.
3. **Versiones:** se fijan en cada fase tras consultar la fuente primaria (releases oficiales, documentación versionada) y se registran en la nota con fecha de consulta; no se asumen. Si una herramienta no soporta Kotlin 2.4.21 se informa y se elige alternativa, no se fuerza.
4. **Un solo gate:** lo que corre en local (`./gradlew check` y los hooks) es lo mismo que corre en CI; CI no agrega reglas escondidas.
5. **Lo que queda en manos de Laura** (configuración de GitHub que no se versiona): protección de rama, `SONAR_TOKEN`, activar Dependabot, CodeQL, secret scanning y private vulnerability reporting.
6. **Alcance de seguridad:** es un proyecto de ejemplo, pero se aplican las prácticas estándar de un repositorio público: sin secretos versionados, dependencias y contenedores escaneados, privilegios mínimos, autenticación en el borde y autorización por dueño. Quedan fuera TLS entre servicios, SASL en Kafka y gestión de secretos con un vault (camino de crecimiento, nota de seguridad).

## Alternativas
| Opción | Por qué no (por ahora) |
|---|---|
| Dejar calidad y seguridad para el final | Contradice el pedido de trabajar ágil; los hallazgos se acumulan y el código se endurece tarde |
| Instalar las herramientas en la máquina | Rompe "solo Docker" y cada colaboradora tendría versiones distintas |
| Solo SonarCloud | Exige cuenta y token desde el primer día; local primero permite probar sin cuenta |
| Mock de OAuth2 en lugar de Keycloak | Más liviano, pero Laura prefirió el emisor real; Keycloak pesa en RAM y disco (se avisó) |
| OWASP Dependency-Check | Más lento y depende de la API de NVD; Trivy y Dependabot cubren dependencias con menos piezas (por verificar en la fase 3) |

## Consecuencias
- `compose.yaml` crece: Loki, Alloy, Grafana y Keycloak en el arranque por defecto; SonarQube solo con `--profile quality`.
- El contrato de la API cambia en la fase 5 (`creatorId` deja de venir en el body); se actualizan tests, README y specs.
- Cada fase agrega una nota en `docs/study/`, y la base del repo exige que el árbol de trabajo pase gitleaks antes de cada commit.
- Hay costo de mantenimiento: Dependabot genera PRs y el quality gate puede fallar por reglas nuevas.

## Avance (2026-10-17)
Fases 0 a 5 hechas (notas 20 a 25); la 3 (CI) sin ejecutar todavía en GitHub; la 5 con Keycloak real verificado por el humo y tokens de prueba en los tests. Hallazgos que cambiaron el diseño: Trivy encontró 12 CVE en la imagen (corregidos); detekt 1.23.8 se ejecuta como `JavaExec` con JDK 21 porque falla bajo un Gradle en JDK 25 (lo habría roto el CI); SonarQube marcó que las dependencias no se verificaban (se agregó `verification-metadata.xml`); `DOCKER_DEFAULT_PLATFORM=linux/amd64` en el shell de esta máquina hacía correr todo emulado.

## Sin verificar (experimentos pendientes, uno por fase)
- Soporte de **Kotlin 2.4.21** y **JDK 25** en detekt, ktlint, el analizador de Kotlin de SonarQube y CodeQL.
- Cómo llegan los logs a Loki desde Docker Desktop en Mac arm64 (ADR 0010).
- Que Keycloak y SonarQube entren en los 7,7 GB de RAM de Docker de esta máquina junto con el resto del stack.
- Que JaCoCo funcione con JDK 25 y los tests con Testcontainers.

## Respaldo
ADR 0007 (infraestructura), ADR 0008 (alcance mínimo), ADR 0010 (observabilidad). Notas de estudio de cada fase (se agregan al ejecutarla).
