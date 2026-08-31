package br.com.itau.challenge.balance.domain.model

import java.time.Instant

/**
 * The projected state of an account's balance — one item per account in DynamoDB, and the
 * payload behind `GET /balances/{accountId}`.
 *
 * [version] is the microsecond timestamp of the transaction that produced this snapshot. It is
 * carried explicitly because it is what makes the write safe under concurrency: the repository
 * only overwrites an item whose stored version is strictly older, so a late or duplicated
 * event can never roll the balance backwards.
 */
data class AccountBalance(
    val accountId: String,
    val owner: String,
    val balance: Money,
    val lastTransactionId: String,
    val version: Long,
) {
    /**
     * When this balance became true — derived from [version] rather than stored alongside it.
     *
     * They are the same instant expressed in two units, and storing both as sources of truth
     * would invite them to drift apart. The persisted item does carry a human-readable copy,
     * but it is written for operators to read and never read back by the application.
     */
    val updatedAt: Instant
        get() = microsToInstant(version)
}
