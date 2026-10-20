#!/usr/bin/env bash

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

max_lines=130
found=0

while IFS= read -r file; do
  lines=$(wc -l < "$file")
  if [ "$lines" -gt "$max_lines" ]; then
    echo "$file: $lines líneas (máximo $max_lines)" >&2
    found=1
  fi
done < <(find services -type f -name '*.kt' -not -path '*/build/*' -not -path '*/.gradle/*' -not -path '*/.kotlin/*')

if [ "$found" -eq 1 ]; then
  echo "Hay archivos demasiado largos: dividilos por responsabilidad." >&2
  exit 1
fi
