#!/bin/bash
set -euo pipefail

BROKERS="${REDPANDA_BROKERS:-redpanda:9092}"
TOPIC_NAME="${TRANSACTIONS_TOPIC:-transacoes-financeiras-processadas}"
PARTITIONS="${TRANSACTIONS_TOPIC_PARTITIONS:-3}"
DLT_NAME="${TOPIC_NAME}.DLT"

echo "Waiting for Redpanda broker at ${BROKERS}..."
until rpk cluster info --brokers "${BROKERS}" >/dev/null 2>&1; do
  echo "  not ready yet, retrying in 2s..."
  sleep 2
done
echo "Redpanda broker is ready."

# Topic auto-creation is disabled by config.sh, so both topics are created explicitly here.
# The application also declares them as KafkaAdmin beans — whichever comes first wins and the
# other is a no-op. Creating them here as well means `make kafka-up` gives a usable broker even
# when the application is not running.
create_topic() {
  local name="$1"
  if rpk topic describe "${name}" --brokers "${BROKERS}" >/dev/null 2>&1; then
    echo "Topic '${name}' already exists, skipping creation."
  else
    echo "Creating topic '${name}' with ${PARTITIONS} partition(s)..."
    rpk topic create "${name}" --brokers "${BROKERS}" --partitions "${PARTITIONS}" --replicas 1
  fi
}

create_topic "${TOPIC_NAME}"

# The dead letter topic must exist up front. Discovering it is missing at the moment a bad
# message needs quarantining is the worst possible time: the recoverer would fail, the error
# handler would retry, and one malformed payload would stall the partition indefinitely.
create_topic "${DLT_NAME}"

# No messages are published here. Unlike a lookup table, an empty balance topic is a valid
# starting state — use `make kafka-produce-transactions-events` for random traffic, or
# `make kafka-produce-scenario` for the ordering and duplicate cases.
echo "Topics ready: '${TOPIC_NAME}' and '${DLT_NAME}'."
