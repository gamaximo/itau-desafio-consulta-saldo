#!/bin/bash
set -euo pipefail

# Publishes a fixed, hand-built sequence of events for a SINGLE account, covering the cases the
# random generator cannot produce.
#
# `produce-transactions-events.sh` draws a fresh random UUID for every event, so no two of its
# messages ever touch the same account — which means it never exercises out-of-order delivery,
# duplicates, or concurrent writes. Those are precisely the cases that matter here, so this
# script pins one account and varies the timestamps instead.
#
# Messages are published WITHOUT a Kafka key, matching the upstream generator. That is the
# adversarial case on purpose: with no key, the three events land on different partitions and
# are consumed concurrently, so their processing order is genuinely unpredictable. Correctness
# cannot come from the broker here — it has to come from the conditional write.

TOPIC="${1:?Usage: produce-scenario.sh <topic> [account-id]}"
ACCOUNT_ID="${2:-$(cat /proc/sys/kernel/random/uuid)}"
OWNER_ID="$(cat /proc/sys/kernel/random/uuid)"
BROKERS="${REDPANDA_BROKERS:-redpanda:9092}"

NOW_US=$(date +%s%6N)
T1=$((NOW_US - 2000000))  # oldest
T2=$((NOW_US - 1000000))  # middle
T3=$NOW_US                # newest — this is the balance that must survive

TX1="$(cat /proc/sys/kernel/random/uuid)"
TX2="$(cat /proc/sys/kernel/random/uuid)"
TX3="$(cat /proc/sys/kernel/random/uuid)"

event() {
  local tx_id="$1" type="$2" amount="$3" status="$4" timestamp="$5" balance="$6"
  printf '{"transaction": {"id": "%s", "type": "%s", "amount": %s, "currency": "BRL", "status": "%s", "timestamp": %s}, "account": {"id": "%s", "owner": "%s", "created_at": %s, "status": "ENABLED", "balance": {"amount": %s, "currency": "BRL"}}}\n' \
    "$tx_id" "$type" "$amount" "$status" "$timestamp" "$ACCOUNT_ID" "$OWNER_ID" "1634874339000000" "$balance"
}

echo "Publishing ordering/idempotency scenario to '${TOPIC}'"
echo "  account: ${ACCOUNT_ID}"
echo

{
  # 1. The middle event arrives first.
  event "$TX2" CREDIT 50.00 APPROVED "$T2" 200.00
  # 2. The oldest event arrives second — out of order. Must NOT overwrite the balance.
  event "$TX1" CREDIT 100.00 APPROVED "$T1" 100.00
  # 3. The newest event. Must win, whatever order the consumer sees it in.
  event "$TX3" DEBIT 25.00 APPROVED "$T3" 300.00
  # 4. Byte-identical replay of the newest event — the duplicate case. Must be a no-op:
  #    its version is equal, not greater, so the strict comparison rejects it.
  event "$TX3" DEBIT 25.00 APPROVED "$T3" 300.00
  # 5. Replay of an older event, the case a consumer rebalance produces in practice.
  event "$TX2" CREDIT 50.00 APPROVED "$T2" 200.00
  # 6. Structurally invalid: `transaction.type` is not a known value. Must be dead-lettered
  #    immediately rather than retried, and must not affect the stored balance.
  printf '{"transaction": {"id": "%s", "type": "TRANSFER", "amount": 10.00, "currency": "BRL", "status": "APPROVED", "timestamp": %s}, "account": {"id": "%s", "owner": "%s", "created_at": 1634874339000000, "status": "ENABLED", "balance": {"amount": 999.99, "currency": "BRL"}}}\n' \
    "$(cat /proc/sys/kernel/random/uuid)" "$T3" "$ACCOUNT_ID" "$OWNER_ID"
} | rpk topic produce "${TOPIC}" --brokers "${BROKERS}" -f '%v\n'

cat <<EOF

Published 6 events (3 distinct transactions, 2 replays, 1 unprocessable).

Expected end state, regardless of the order the consumer happens to process them in:

  GET /balances/${ACCOUNT_ID}
    balance.amount     300.00        (from the newest transaction, not the last one delivered)
    updated_at         derived from ${T3}

  The invalid event lands on '${TOPIC}.DLT' and leaves the balance untouched.

Verify with:
  curl -s http://localhost:8080/balances/${ACCOUNT_ID}
  make kafka-consume TOPIC=${TOPIC}.DLT
EOF
