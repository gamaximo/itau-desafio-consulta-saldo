#!/bin/bash
set -euo pipefail

# Publica uma sequência fixa, montada à mão, de eventos para UMA ÚNICA conta, cobrindo os casos
# que o gerador aleatório não consegue produzir.
#
# O `produce-transactions-events.sh` sorteia um UUID novo a cada evento, então duas de suas
# mensagens nunca tocam a mesma conta — ou seja, ele nunca exercita entrega fora de ordem,
# duplicatas ou concorrência. Justamente esses são os casos que importam aqui, então este script
# fixa uma conta e varia os timestamps.
#
# As mensagens são publicadas SEM chave Kafka, igual ao gerador oficial. Esse é o caso adverso de
# propósito: sem chave, os três eventos caem em partições diferentes e são consumidos
# concorrentemente, então a ordem de processamento é genuinamente imprevisível. A corretude aqui
# não pode vir do broker; ela tem que vir da escrita condicional.

TOPIC="${1:?Uso: produce-scenario.sh <topico> [account-id]}"
ACCOUNT_ID="${2:-$(cat /proc/sys/kernel/random/uuid)}"
OWNER_ID="$(cat /proc/sys/kernel/random/uuid)"
BROKERS="${REDPANDA_BROKERS:-redpanda:9092}"

NOW_US=$(date +%s%6N)
T1=$((NOW_US - 2000000))  # mais antigo
T2=$((NOW_US - 1000000))  # intermediário
T3=$NOW_US                # mais recente — este é o saldo que deve sobreviver

TX1="$(cat /proc/sys/kernel/random/uuid)"
TX2="$(cat /proc/sys/kernel/random/uuid)"
TX3="$(cat /proc/sys/kernel/random/uuid)"

event() {
  local tx_id="$1" type="$2" amount="$3" status="$4" timestamp="$5" balance="$6"
  printf '{"transaction": {"id": "%s", "type": "%s", "amount": %s, "currency": "BRL", "status": "%s", "timestamp": %s}, "account": {"id": "%s", "owner": "%s", "created_at": %s, "status": "ENABLED", "balance": {"amount": %s, "currency": "BRL"}}}\n' \
    "$tx_id" "$type" "$amount" "$status" "$timestamp" "$ACCOUNT_ID" "$OWNER_ID" "1634874339000000" "$balance"
}

echo "Publicando cenário de ordenação/idempotência em '${TOPIC}'"
echo "  conta: ${ACCOUNT_ID}"
echo

{
  # 1. O evento intermediário chega primeiro.
  event "$TX2" CREDIT 50.00 APPROVED "$T2" 200.00
  # 2. O evento mais antigo chega em segundo — fora de ordem. NÃO pode sobrescrever o saldo.
  event "$TX1" CREDIT 100.00 APPROVED "$T1" 100.00
  # 3. O evento mais recente. Tem que vencer, em qualquer ordem que o consumidor o veja.
  event "$TX3" DEBIT 25.00 APPROVED "$T3" 300.00
  # 4. Reenvio byte a byte idêntico do evento mais recente — o caso de duplicata. Tem que ser
  #    no-op: a versão é igual, não maior, então a comparação estrita rejeita.
  event "$TX3" DEBIT 25.00 APPROVED "$T3" 300.00
  # 5. Reenvio de um evento mais antigo, o caso que um rebalanceamento de consumidores produz na
  #    prática.
  event "$TX2" CREDIT 50.00 APPROVED "$T2" 200.00
  # 6. Estruturalmente inválido: `transaction.type` não é um valor conhecido. Tem que ir para o
  #    dead letter topic de imediato, sem retry, e não pode afetar o saldo armazenado.
  printf '{"transaction": {"id": "%s", "type": "TRANSFER", "amount": 10.00, "currency": "BRL", "status": "APPROVED", "timestamp": %s}, "account": {"id": "%s", "owner": "%s", "created_at": 1634874339000000, "status": "ENABLED", "balance": {"amount": 999.99, "currency": "BRL"}}}\n' \
    "$(cat /proc/sys/kernel/random/uuid)" "$T3" "$ACCOUNT_ID" "$OWNER_ID"
} | rpk topic produce "${TOPIC}" --brokers "${BROKERS}" -f '%v\n'

cat <<EOF

Publicados 6 eventos (3 transações distintas, 2 reenvios, 1 inprocessável).

Estado final esperado, independentemente da ordem em que o consumidor processá-los:

  GET /balances/${ACCOUNT_ID}
    balance.amount     300.00        (da transação mais recente, não da última entregue)
    updated_at         derivado de ${T3}

  O evento inválido cai em '${TOPIC}.DLT' e deixa o saldo intacto.

Verifique com:
  curl -s http://localhost:8080/balances/${ACCOUNT_ID}
  make kafka-consume TOPIC=${TOPIC}.DLT
EOF
