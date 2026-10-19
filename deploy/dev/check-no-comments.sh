#!/usr/bin/env bash

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

kotlin_files=()
while IFS= read -r file; do kotlin_files+=("$file"); done < <(find services spikes -type f \( -name '*.kt' -o -name '*.kts' \) -not -path '*/build/*' -not -path '*/.gradle/*' -not -path '*/.kotlin/*')

config_files=()
while IFS= read -r file; do config_files+=("$file"); done < <(find . -type f \( -name '*.sh' -o -name '*.yaml' -o -name '*.yml' -o -name '*.toml' -o -name 'Dockerfile' -o -name '.gitignore' -o -name '.editorconfig' -o -name 'CODEOWNERS' -o -name 'pre-commit' \) -not -path './.git/*' -not -path '*/build/*' -not -path '*/.gradle/*' -not -path './.idea/*')

found=0

if grep -nE '^\s*(//|/\*|\*/|\* )|[^:"]\s//\s' "${kotlin_files[@]}" /dev/null; then found=1; fi

if grep -nE '^\s*#([^!]|$)|\S\s+#\s' "${config_files[@]}" /dev/null | grep -vE '#\s*v[0-9]'; then found=1; fi

if [ "$found" -eq 1 ]; then
  echo "Hay comentarios en el código: el código se lee solo; el porqué va en un ADR, una nota o un nombre." >&2
  exit 1
fi
