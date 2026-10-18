#!/usr/bin/env bash

set -euo pipefail
cd "$(git rev-parse --show-toplevel)/services"

fresh_gradle_home="$(mktemp -d)"
trap 'rm -rf "$fresh_gradle_home"' EXIT
export GRADLE_USER_HOME="$fresh_gradle_home"

echo ">> Regenerando con una caché de Gradle vacía ($fresh_gradle_home); descarga todo, puede tardar varios minutos"
./gradlew --write-verification-metadata sha256 check bootJar --rerun-tasks
echo ">> Listo. Revisá: git diff --stat services/gradle/verification-metadata.xml"
