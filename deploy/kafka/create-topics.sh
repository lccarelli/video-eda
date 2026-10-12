#!/usr/bin/env bash

set -euo pipefail

BOOTSTRAP="${BOOTSTRAP_SERVER:-localhost:9092}"
KT="/opt/kafka/bin/kafka-topics.sh"
KC="/opt/kafka/bin/kafka-configs.sh"
WORKER_GROUP="${WORKER_GROUP:-videos-processing-worker}"

crear() {
  local nombre="$1"; shift
  local cfg=()
  for c in "$@"; do cfg+=(--config "$c"); done
  "$KT" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
        --topic "$nombre" --partitions 3 --replication-factor 1 "${cfg[@]}"
}

echo ">> Esperando al broker en $BOOTSTRAP"
until "$KT" --bootstrap-server "$BOOTSTRAP" --list >/dev/null 2>&1; do sleep 1; done

echo ">> Topics de dominio (retención infinita: de acá se reconstruyen los read models)"
crear videos.video        retention.ms=-1
crear interactions.comment retention.ms=-1
crear feed.follow         retention.ms=-1

echo ">> Cola de trabajo del worker de video (retención por defecto)"
crear videos.processing

echo ">> DLQ: una por contexto consumidor, más la del worker (retención por defecto)"
crear interactions.dlq
crear feed.dlq
crear notifications.dlq
crear videos.processing.dlq

echo ">> Share group del worker de video: sin esto arrancaría en 'latest' y perdería lo publicado antes de que el grupo exista (nota 07)"
"$KC" --bootstrap-server "$BOOTSTRAP" --alter --entity-type groups --entity-name "$WORKER_GROUP" \
      --add-config share.auto.offset.reset=earliest

echo ">> Topics creados:"
"$KT" --bootstrap-server "$BOOTSTRAP" --list | grep -v '^__' | sort
