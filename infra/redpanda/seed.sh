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

# A criação automática de tópicos é desabilitada pelo config.sh, então os dois tópicos são
# criados explicitamente aqui. A aplicação também os declara como beans do KafkaAdmin — quem
# chegar primeiro cria, e o outro vira no-op. Criá-los aqui também significa que `make kafka-up`
# entrega um broker utilizável mesmo com a aplicação parada.
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

# O dead letter topic precisa existir de antemão. Descobrir que ele falta no momento em que uma
# mensagem ruim precisa ser posta em quarentena é o pior instante possível: o recoverer falharia,
# o error handler retentaria, e um único payload malformado travaria a partição indefinidamente.
create_topic "${DLT_NAME}"

# Nenhuma mensagem é publicada aqui. Diferente de uma tabela de consulta, um tópico de saldos
# vazio é um estado inicial válido — use `make kafka-produce-transactions-events` para tráfego
# aleatório, ou `make kafka-produce-scenario` para os casos de ordenação e duplicata.
echo "Topics ready: '${TOPIC_NAME}' and '${DLT_NAME}'."
