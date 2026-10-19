#!/usr/bin/env bash

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

SONAR_URL="${SONAR_URL:-http://localhost:9000}"
ADMIN_PASSWORD="${SONAR_ADMIN_PASSWORD:-Videoeda-Sonar-Dev-2026!}"
PROJECT_KEY="video-eda"
TOKEN_NAME="analisis-local"

unset DOCKER_DEFAULT_PLATFORM

if [ "${1:-}" = "--down" ]; then
  docker compose --profile quality stop sonarqube
  exit 0
fi

fail() { echo "SONAR FALLÓ: $*" >&2; exit 1; }
json_field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }

echo ">> Levantando SonarQube (perfil quality)"
docker compose --profile quality up -d sonarqube

echo ">> Esperando que esté UP"
for _ in $(seq 1 90); do
  curl -s -m 3 "$SONAR_URL/api/system/status" | grep -q '"status":"UP"' && break
  sleep 3
done
curl -s -m 3 "$SONAR_URL/api/system/status" | grep -q '"status":"UP"' || fail "SonarQube no llegó a UP en 4,5 minutos"

change_status="$(curl -s -o "${TMPDIR:-/tmp}/sonar-change-password.out" -w '%{http_code}' -u admin:admin -X POST \
  "$SONAR_URL/api/users/change_password" -d login=admin -d previousPassword=admin --data-urlencode "password=$ADMIN_PASSWORD")"
case "$change_status" in
  204|401) ;;
  *) fail "no pude cambiar la contraseña de fábrica (HTTP $change_status): $(cat "${TMPDIR:-/tmp}/sonar-change-password.out")" ;;
esac
rm -f "${TMPDIR:-/tmp}/sonar-change-password.out"
curl -sf -u "admin:$ADMIN_PASSWORD" "$SONAR_URL/api/authentication/validate" | grep -q '"valid":true' \
  || fail "no pude autenticarme como admin con la contraseña esperada (¿otra contraseña en un volumen viejo?)"

curl -s -o /dev/null -u "admin:$ADMIN_PASSWORD" -X POST "$SONAR_URL/api/projects/create" \
  -d "name=$PROJECT_KEY" -d "project=$PROJECT_KEY" || true

curl -s -o /dev/null -u "admin:$ADMIN_PASSWORD" -X POST "$SONAR_URL/api/user_tokens/revoke" -d "name=$TOKEN_NAME" || true
token="$(curl -sf -u "admin:$ADMIN_PASSWORD" -X POST "$SONAR_URL/api/user_tokens/generate" -d "name=$TOKEN_NAME" | json_field token)"
[ -n "$token" ] || fail "no pude generar el token de análisis"

echo ">> Tests, cobertura y análisis (puede tardar unos minutos)"
(cd services && SONAR_TOKEN="$token" ./gradlew check sonar \
  -Dsonar.host.url="$SONAR_URL" -Dsonar.qualitygate.wait=true) || gradle_failed=1

echo ">> Quality gate"
gate="$(curl -sf -u "admin:$ADMIN_PASSWORD" "$SONAR_URL/api/qualitygates/project_status?projectKey=$PROJECT_KEY")" || fail "no pude leer el quality gate"
status="$(echo "$gate" | sed -n 's/^{"projectStatus":{"status":"\([A-Z]*\)".*/\1/p')"
echo "   estado: ${status:-desconocido}"
echo "$gate" | sed 's/},{/}\n{/g' | sed -n 's/.*"status":"\(ERROR\)".*"metricKey":"\([^"]*\)".*"actualValue":"\([^"]*\)".*/   falla: \2 = \3/p'

echo ">> Métricas"
measures="$(curl -sf -u "admin:$ADMIN_PASSWORD" \
  "$SONAR_URL/api/measures/component?component=$PROJECT_KEY&metricKeys=bugs,vulnerabilities,security_hotspots,code_smells,coverage,duplicated_lines_density,ncloc")" || true
echo "$measures" | sed 's/},{/}\n{/g' | sed -n 's/.*"metric":"\([^"]*\)","value":"\([^"]*\)".*/   \1 = \2/p'
echo ">> Detalle: $SONAR_URL/dashboard?id=$PROJECT_KEY"

[ "${gradle_failed:-0}" = "0" ] || fail "Gradle terminó con error (ver arriba)"
[ "$status" = "OK" ] || fail "el quality gate no está en OK"
echo ">> OK"
