#!/usr/bin/env bash

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

HADOLINT_IMAGE="ghcr.io/hadolint/hadolint:v2.15.1"
SHELLCHECK_IMAGE="koalaman/shellcheck:v0.11.0"
ACTIONLINT_IMAGE="rhysd/actionlint:1.7.12"
ZIZMOR_IMAGE="ghcr.io/zizmorcore/zizmor:1.31.1"

echo ">> Sin comentarios en el código"
./deploy/dev/check-no-comments.sh

echo ">> Archivos de hasta 130 líneas"
./deploy/dev/check-file-length.sh

echo ">> Kotlin: ktlint + detekt"
(cd services && ./gradlew ktlintCheck detekt)

echo ">> Gradle: la verificación de dependencias admite lo que baja el IDE al sincronizar (fuentes)"
(cd services && ./gradlew -q -I "../deploy/dev/gradle/ide-sync-sources.init.gradle" :videos:ideSyncSources)

echo ">> Dockerfiles: hadolint"
while IFS= read -r dockerfile; do
  echo "   $dockerfile"
  docker run --rm -i "$HADOLINT_IMAGE" hadolint - < "$dockerfile"
done < <(find services -name Dockerfile -not -path '*/build/*')

echo ">> Scripts de shell: shellcheck"
scripts=()
while IFS= read -r script; do scripts+=("$script"); done < <(find deploy .githooks -type f \( -name '*.sh' -o -name 'pre-commit' \))
docker run --rm -v "$PWD:/mnt" -w /mnt "$SHELLCHECK_IMAGE" "${scripts[@]}"

if [ -d .github/workflows ]; then
  echo ">> Workflows de GitHub Actions: actionlint (sintaxis) y zizmor (seguridad)"
  docker run --rm -v "$PWD:/repo" -w /repo "$ACTIONLINT_IMAGE" -color
  docker run --rm -v "$PWD:/repo" -w /repo "$ZIZMOR_IMAGE" --no-online-audits --min-severity low .github
fi

echo ">> Todo en orden"
