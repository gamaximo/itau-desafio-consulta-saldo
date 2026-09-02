#!/bin/bash
set -euo pipefail

# Reprocessa o tópico de transações subindo uma instância **adicional** da aplicação, com um
# group-id exclusivo, enquanto a instância de produção segue atendendo normalmente.
#
# Grupos de consumo têm offsets independentes: a instância de replay lê o tópico desde o início
# sem interferir no progresso de quem está servindo. As duas gravam na mesma tabela, o que é
# inofensivo porque a escrita condicional descarta o que já foi aplicado — reprocessar produz um
# estado idêntico, não duplicado.
#
# A alternativa seria rebobinar o offset do próprio grupo de produção, mas isso exige parar a
# aplicação: o Kafka recusa mover o offset de um grupo com membros ativos. Este caminho não pede
# janela de indisponibilidade.

TOPIC="${TOPIC:-transacoes-financeiras-processadas}"
GROUP="${GROUP:-balance-replay-$(date +%Y%m%d-%H%M%S)}"
CONTAINER="balance-replay"
# Porta própria para acompanhar o progresso pelo actuator sem colidir com a instância de produção.
PORTA="${PORTA:-8090}"
PROJETO="$(basename "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)")"
REDE="${PROJETO}_default"
IMAGEM="${IMAGEM:-${PROJETO}-app}"

log() { printf '  %s\n' "$1"; }

encerrar() {
  docker stop "$CONTAINER" >/dev/null 2>&1 || true
}
trap encerrar EXIT

echo "Reprocessamento do tópico '${TOPIC}'"
log "group-id do replay: ${GROUP}"
log "a instância de produção continua no ar durante todo o processo"
echo

if ! docker image inspect "$IMAGEM" >/dev/null 2>&1; then
  log "imagem '${IMAGEM}' não encontrada — rode 'make build' antes"
  exit 1
fi

docker run -d --rm --name "$CONTAINER" --network "$REDE" \
  -p "${PORTA}:8080" \
  -e DYNAMODB_ENDPOINT=http://dynamodb:8000 \
  -e KAFKA_BOOTSTRAP_SERVERS=redpanda:9092 \
  -e BALANCE_TABLE_NAME="${BALANCE_TABLE_NAME:-AccountBalances}" \
  -e TRANSACTIONS_TOPIC="$TOPIC" \
  -e KAFKA_CONSUMER_GROUP_ID="$GROUP" \
  -e REPLAY_MODE=true \
  "$IMAGEM" >/dev/null

log "instância de replay iniciada (actuator em localhost:${PORTA})"

for _ in $(seq 1 40); do
  curl -sf "http://localhost:${PORTA}/actuator/health" >/dev/null 2>&1 && break
  sleep 2
done

# `docker compose exec` em vez de procurar o contêiner por nome: o filtro `name=redpanda` casa
# também com `redpanda-console`, que não tem o rpk instalado.
descrever_grupo() {
  docker compose exec -T redpanda rpk group describe "$GROUP" --brokers redpanda:9092 2>/dev/null
}

# O lag só existe depois que o grupo se registra no broker e recebe as partições.
for _ in $(seq 1 30); do
  descrever_grupo | grep -q TOTAL-LAG && break
  sleep 2
done

log "acompanhando o consumo..."
ANTERIOR=""
for _ in $(seq 1 300); do
  LAG=$(descrever_grupo | awk '/TOTAL-LAG/ {print $2}')
  [ -z "$LAG" ] && { sleep 2; continue; }
  [ "$LAG" != "$ANTERIOR" ] && { log "lag: ${LAG}"; ANTERIOR="$LAG"; }
  [ "$LAG" = "0" ] && { echo; log "reprocessamento concluído"; break; }
  sleep 2
done

echo
log "resultado — 'duplicate_discarded' alto e 'applied' baixo é o esperado:"
curl -s "http://localhost:${PORTA}/actuator/prometheus" 2>/dev/null \
  | grep -E '^balance_transactions_processed_total' | sed 's/^/    /' \
  || log "(não foi possível ler as métricas em localhost:${PORTA})"
