package br.com.itau.challenge.balance.adapter.output.dynamodb

import br.com.itau.challenge.balance.domain.model.AccountBalance
import br.com.itau.challenge.balance.domain.model.Money
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.math.BigDecimal

internal const val ACCOUNT_ID_ATTRIBUTE = "accountId"
internal const val OWNER_ATTRIBUTE = "owner"
internal const val BALANCE_AMOUNT_ATTRIBUTE = "balanceAmount"
internal const val BALANCE_CURRENCY_ATTRIBUTE = "balanceCurrency"
internal const val LAST_TRANSACTION_ID_ATTRIBUTE = "lastTransactionId"
internal const val VERSION_ATTRIBUTE = "version"
internal const val UPDATED_AT_ATTRIBUTE = "updatedAt"

/**
 * Mapeamento entre o modelo de domínio e o formato do item no DynamoDB, concentrado num só lugar
 * para que a leitura e a escrita nunca discordem sobre nomes de atributo ou codificação.
 */

internal fun AccountBalance.toItem(): Map<String, AttributeValue> =
    mapOf(
        ACCOUNT_ID_ATTRIBUTE to AttributeValue.builder().s(accountId).build(),
        OWNER_ATTRIBUTE to AttributeValue.builder().s(owner).build(),
        // Armazenado como Number do DynamoDB a partir da string decimal exata. `toPlainString`
        // evita notação científica, que o DynamoDB rejeitaria, e não perde nenhum dígito — ao
        // contrário do que aconteceria passando o valor por um Double.
        BALANCE_AMOUNT_ATTRIBUTE to AttributeValue.builder().n(balance.amount.toPlainString()).build(),
        BALANCE_CURRENCY_ATTRIBUTE to AttributeValue.builder().s(balance.currency).build(),
        LAST_TRANSACTION_ID_ATTRIBUTE to AttributeValue.builder().s(lastTransactionId).build(),
        VERSION_ATTRIBUTE to AttributeValue.builder().n(version.toString()).build(),
        // Derivado, somente escrita: nunca é lido de volta pela aplicação. Existe para que quem
        // abrir este item num console ou numa consulta de suporte veja uma data em vez de um
        // contador de microssegundos com 16 dígitos. `version` continua sendo a única fonte de
        // verdade para a ordenação.
        UPDATED_AT_ATTRIBUTE to AttributeValue.builder().s(updatedAt.toString()).build(),
    )

internal fun Map<String, AttributeValue>.toAccountBalance(): AccountBalance =
    AccountBalance(
        accountId = getValue(ACCOUNT_ID_ATTRIBUTE).s(),
        owner = getValue(OWNER_ATTRIBUTE).s(),
        balance =
            Money(
                amount = BigDecimal(getValue(BALANCE_AMOUNT_ATTRIBUTE).n()),
                currency = getValue(BALANCE_CURRENCY_ATTRIBUTE).s(),
            ),
        lastTransactionId = getValue(LAST_TRANSACTION_ID_ATTRIBUTE).s(),
        version = getValue(VERSION_ATTRIBUTE).n().toLong(),
    )
