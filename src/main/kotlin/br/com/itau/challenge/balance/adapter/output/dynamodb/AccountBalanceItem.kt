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
 * Mapping between the domain model and the DynamoDB item shape, kept in one place so that the
 * reader and the writer can never disagree about attribute names or encodings.
 */

internal fun AccountBalance.toItem(): Map<String, AttributeValue> =
    mapOf(
        ACCOUNT_ID_ATTRIBUTE to AttributeValue.builder().s(accountId).build(),
        OWNER_ATTRIBUTE to AttributeValue.builder().s(owner).build(),
        // Stored as a DynamoDB Number from the exact decimal string. `toPlainString` avoids
        // scientific notation, which DynamoDB would reject, and never loses a digit — unlike
        // routing the value through a Double.
        BALANCE_AMOUNT_ATTRIBUTE to AttributeValue.builder().n(balance.amount.toPlainString()).build(),
        BALANCE_CURRENCY_ATTRIBUTE to AttributeValue.builder().s(balance.currency).build(),
        LAST_TRANSACTION_ID_ATTRIBUTE to AttributeValue.builder().s(lastTransactionId).build(),
        VERSION_ATTRIBUTE to AttributeValue.builder().n(version.toString()).build(),
        // Derived, write-only: never read back by the application. It exists so that whoever
        // opens this item in a console or a support query sees a date instead of a 16-digit
        // microsecond count. `version` remains the single source of truth for ordering.
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
