#!/usr/bin/env bash

set -euo pipefail

API="${API_URL:-http://localhost:8081}"
GRAFANA="${GRAFANA_URL:-http://localhost:3000}"
KEYCLOAK="${KEYCLOAK_URL:-http://localhost:8180}"
LUCIA_PASSWORD="${LUCIA_PASSWORD:-lucia-dev-1}"
MATEO_PASSWORD="${MATEO_PASSWORD:-mateo-dev-1}"
GRAFANA_USER="${GRAFANA_USER:-admin}"
GRAFANA_PASSWORD="${GRAFANA_ADMIN_PASSWORD:-videoeda-dev}"
CORRELATION_ID="cor_01a13161-13c4-7752-b78c-$(openssl rand -hex 6)"

fail() { echo "SMOKE FALLÓ: $*" >&2; exit 1; }
json_field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }

token_for() {
  curl -sf -X POST "$KEYCLOAK/realms/video-eda/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=video-eda-cli -d "username=$1" -d "password=$2" | json_field access_token
}
http_status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

echo ">> Pidiendo tokens a Keycloak (lucia y mateo)"
lucia_token="$(token_for lucia "$LUCIA_PASSWORD")" || true
[ -n "$lucia_token" ] || fail "Keycloak no emitió el token de lucia (¿contraseña o Keycloak caído?)"
mateo_token="$(token_for mateo "$MATEO_PASSWORD")" || true
[ -n "$mateo_token" ] || fail "Keycloak no emitió el token de mateo (¿contraseña o Keycloak caído?)"

echo ">> SEC-01: publicar sin token se rechaza"
anonymous_status="$(http_status -X POST "$API/videos" -H "Idempotency-Key: $(uuidgen | tr '[:upper:]' '[:lower:]')" \
  -H 'Content-Type: application/json' -d '{"title":"Sin token","fileSizeBytes":1}')"
[ "$anonymous_status" = "401" ] || fail "POST sin token devolvió $anonymous_status (se esperaba 401)"

echo ">> POST /videos con X-Correlation-Id $CORRELATION_ID"
response="$(curl -sf -X POST "$API/videos" \
  -H "Authorization: Bearer $lucia_token" \
  -H "Idempotency-Key: $(uuidgen | tr '[:upper:]' '[:lower:]')" \
  -H "X-Correlation-Id: $CORRELATION_ID" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Paella valenciana","fileSizeBytes":1048576}')" || fail "el POST con el token de lucia no devolvió 2xx"
video_id="$(echo "$response" | json_field videoId)"
[ -n "$video_id" ] || fail "la respuesta no trae videoId: $response"

echo ">> Esperando que el worker deje $video_id en PUBLISHED"
status=""
for _ in $(seq 1 60); do
  status="$(curl -sf "$API/videos/$video_id" | json_field status || true)"
  [ "$status" = "PUBLISHED" ] && break
  sleep 1
done
[ "$status" = "PUBLISHED" ] || fail "el video quedó en '$status' tras 60 s"

echo ">> Buscando el flujo en Loki (vía Grafana) por $CORRELATION_ID"
query="$(printf '{service=~".+"} |= "%s"' "$CORRELATION_ID" | od -An -tx1 | tr -d ' \n' | sed 's/../%&/g')"
lines=0
for _ in $(seq 1 30); do
  body="$(curl -sf -u "$GRAFANA_USER:$GRAFANA_PASSWORD" \
    "$GRAFANA/api/datasources/proxy/uid/loki/loki/api/v1/query_range?query=$query&limit=100&since=15m" || true)"
  lines="$(echo "$body" | grep -o "$CORRELATION_ID" | wc -l | tr -d ' ')" || true
  [ "$lines" -ge 4 ] && break
  sleep 1
done
[ "$lines" -ge 4 ] || fail "Loki devolvió $lines líneas con $CORRELATION_ID (se esperaban al menos 4)"

echo ">> SEC-12: mirar el video es público"
[ "$(http_status "$API/videos/$video_id")" = "200" ] || fail "GET sin token no devolvió 200"

echo ">> SEC-10: otro creator no puede borrar el video"
foreign_status="$(http_status -X DELETE "$API/videos/$video_id" -H "Authorization: Bearer $mateo_token")"
[ "$foreign_status" = "403" ] || fail "DELETE de mateo devolvió $foreign_status (se esperaba 403)"

echo ">> El dueño sí puede borrarlo"
owner_status="$(http_status -X DELETE "$API/videos/$video_id" -H "Authorization: Bearer $lucia_token")"
[ "$owner_status" = "204" ] || fail "DELETE de lucia devolvió $owner_status (se esperaba 204)"

echo ">> OK: $video_id PUBLISHED, $lines líneas del flujo en Loki y la autorización con tokens reales de Keycloak"
