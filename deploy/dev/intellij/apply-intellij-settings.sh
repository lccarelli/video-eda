#!/usr/bin/env bash

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

target_dir="${IDEA_DIR:-.idea}"
sdk_name="corretto-25"

if [[ "${1:-}" != "--force" ]] && pgrep -f "IntelliJ IDEA" >/dev/null 2>&1; then
  echo "IntelliJ está abierto: cerralo y volvé a correr esto (o usá --force)." >&2
  exit 1
fi

if ! grep -rqs "<name value=\"$sdk_name\"" "$HOME"/Library/Application\ Support/JetBrains/*/options/jdk.table.xml 2>/dev/null; then
  echo "AVISO: no encuentro un SDK llamado '$sdk_name' en tu IntelliJ." >&2
  echo "  Agregalo en File > Project Structure > SDKs, con el JDK de: $HOME/.sdkman/candidates/java/25.0.1.0+8-amzn" >&2
  echo "  (o instalalo con: sdk env install)." >&2
fi

mkdir -p "$target_dir"
if [[ -f "$target_dir/gradle.xml" ]]; then
  cp "$target_dir/gradle.xml" "$target_dir/gradle.xml.bak"
  echo "Copia de seguridad: $target_dir/gradle.xml.bak"
fi
cp deploy/dev/intellij/gradle.xml "$target_dir/gradle.xml"
echo "Aplicado: $target_dir/gradle.xml (Gradle JVM $sdk_name, compilar y probar con Gradle)."
