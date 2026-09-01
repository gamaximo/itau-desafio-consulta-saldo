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

# A tabela é criada vazia de propósito: saldos não são dados de seed, eles são projetados a
# partir do tópico de transações. Um saldo pré-carregado seria uma ficção sem versão por trás, e o
# primeiro evento real daquela conta teria que ser comparado contra ele.
if aws dynamodb describe-table --table-name "${TABLE_NAME}" --endpoint-url "${ENDPOINT_URL}" --region "${REGION}" >/dev/null 2>&1; then
  echo "Table '${TABLE_NAME}' already exists, skipping creation."
else
  echo "Creating table '${TABLE_NAME}'..."
  # Esquema de chave com um único atributo: `accountId` como partition key, sem sort key.
  #
  # O único padrão de acesso deste serviço é "me dê o saldo atual desta conta", que uma partition
  # key responde com um GetItem de poucos milissegundos. Uma sort key só faria sentido para manter
  # histórico de saldos por conta — outro requisito, com outro perfil de custo, e que
  # transformaria o caminho de leitura em "buscar vários e escolher o mais recente" em vez de uma
  # consulta direta.
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
