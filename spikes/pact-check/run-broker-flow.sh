#!/usr/bin/env bash

set -uo pipefail
cd "$(dirname "$0")"

CLI_IMAGE=pactfoundation/pact-cli:1.5.0.5
BROKER=http://localhost:9292
COMPOSE=(docker compose -p pactcheck -f ../../compose.yaml -f broker.compose.yaml)
cli() { docker run --rm --network container:pactcheck-broker -v "$PWD/build/pacts:/pacts" "$CLI_IMAGE" pact-broker "$@" --broker-base-url "$BROKER"; }
step() { printf '\n######## %s\n' "$*"; }

cleanup() { step "docker compose down -v (proyecto pactcheck)"; "${COMPOSE[@]}" down -v; }
trap cleanup EXIT

step "1. Levantar postgres y pact-broker"
"${COMPOSE[@]}" up -d --wait postgres pact-broker || exit 1
for i in $(seq 1 30); do curl -sf "$BROKER/diagnostic/status/heartbeat" >/dev/null && break; sleep 1; done
curl -s "$BROKER/diagnostic/status/heartbeat"; echo

step "2. Consumers generan pacts (./gradlew clean test)"
./gradlew --no-daemon -q clean test >/dev/null 2>&1; ls build/pacts

step "3. Crear environment production y publicar pacts de feed y notifications v1.0.0"
cli create-environment --name production --display-name Production
cli publish /pacts/feed-videos.json /pacts/notifications-videos.json --consumer-app-version 1.0.0 --branch main
cli record-deployment --pacticipant feed --version 1.0.0 --environment production
cli record-deployment --pacticipant notifications --version 1.0.0 --environment production

verify() {
  local v=$1; shift
  ./gradlew --no-daemon providerBrokerVerification -PpactBrokerUrl=$BROKER -PproviderVersion="$v" "$@" 2>&1 \
    | grep -E "PASSED|FAILED|Verifying|has a matching|includes message|Failures:|metadata:|body:|published|Published|BUILD" | cut -c1-220
}
decide() { cli can-i-deploy --pacticipant videos --version "$1" --to-environment production; echo "exit code can-i-deploy videos $1 = $?"; }

step "4. CTR-01 camino verde: videos 1.0.0 verifica y can-i-deploy"
verify 1.0.0
decide 1.0.0
cli record-deployment --pacticipant videos --version 1.0.0 --environment production

step "5. CTR-01 camino roto: videos 1.1.0 sin creatorId"
verify 1.1.0 -Pbreak=creatorId-missing
decide 1.1.0

step "6. CTR-02: videos 1.2.0 agrega un campo opcional"
verify 1.2.0 -Pbreak=extra-optional-field
decide 1.2.0

step "7. CTR-05 vía metadata: videos 1.3.0 usa creatorId como key"
verify 1.3.0 -Pbreak=key-wrong
decide 1.3.0

step "8. Matriz final"
cli can-i-deploy --pacticipant videos --version 1.1.0 --to-environment production --output table || true
