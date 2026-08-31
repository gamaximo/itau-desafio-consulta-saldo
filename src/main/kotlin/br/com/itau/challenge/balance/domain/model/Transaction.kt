package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import java.math.BigDecimal

/**
 * A financial transaction as decided by the authorizer.
 *
 * [timestamp] is the epoch time in **microseconds** at which the authorizer settled this
 * transaction. It is the ordering key of the whole system: Kafka only guarantees order within
 * a partition, so the timestamp — not the arrival order — decides which snapshot is newer.
 */
data class Transaction(
    val id: String,
    val type: TransactionType,
    val amount: Money,
    val status: TransactionStatus,
    val timestamp: Long,
) {
    init {
        requireUuid(id, "transaction.id")
        requirePositiveTimestamp(timestamp, "transaction.timestamp")
        if (amount.amount < BigDecimal.ZERO) {
            throw InvalidTransactionEventException("transaction.amount must not be negative, got ${amount.amount}")
        }
    }
}
