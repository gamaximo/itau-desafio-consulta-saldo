#!/bin/bash
set -euo pipefail

ENDPOINT_URL="${DYNAMODB_ENDPOINT_URL:-http://dynamodb:8000}"
TABLE_NAME="${BALANCE_TABLE_NAME:-AccountBalances}"
REGION="${AWS_DEFAULT_REGION:-us-east-1}"

echo "Waiting for DynamoDB Local at ${ENDPOINT_URL}..."
until aws dynamodb list-tables --endpoint-url "${ENDPOINT_URL}" --region "${REGION}" >/dev/null 2>&1; do
  echo "  not ready yet, retrying in 2s..."
  sleep 2
done
echo "DynamoDB Local is ready."

# The table is intentionally created empty: balances are not seeded data, they are projected
# from the transaction topic. A pre-seeded balance would be a fiction with no version behind
# it, and the first real event for that account would have to be compared against it.
if aws dynamodb describe-table --table-name "${TABLE_NAME}" --endpoint-url "${ENDPOINT_URL}" --region "${REGION}" >/dev/null 2>&1; then
  echo "Table '${TABLE_NAME}' already exists, skipping creation."
else
  echo "Creating table '${TABLE_NAME}'..."
  # Single-attribute key schema: `accountId` as the partition key, no sort key.
  #
  # The only access pattern this service has is "give me the current balance of one account",
  # which a partition key answers in a single-digit-millisecond GetItem. A sort key would only
  # make sense for keeping a history of balances per account — a different requirement, with a
  # different cost profile, and one that would make the read path scan-and-pick-latest instead
  # of a direct lookup.
  aws dynamodb create-table \
    --table-name "${TABLE_NAME}" \
    --attribute-definitions AttributeName=accountId,AttributeType=S \
    --key-schema AttributeName=accountId,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST \
    --endpoint-url "${ENDPOINT_URL}" \
    --region "${REGION}" >/dev/null
  aws dynamodb wait table-exists \
    --table-name "${TABLE_NAME}" \
    --endpoint-url "${ENDPOINT_URL}" \
    --region "${REGION}"
  echo "Table '${TABLE_NAME}' created."
fi

COUNT=$(aws dynamodb scan \
  --table-name "${TABLE_NAME}" \
  --endpoint-url "${ENDPOINT_URL}" \
  --region "${REGION}" \
  --select COUNT \
  --query 'Count' \
  --output text)

echo "Ready. '${TABLE_NAME}' currently holds ${COUNT} balance(s)."
