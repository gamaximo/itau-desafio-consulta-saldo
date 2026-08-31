package br.com.itau.challenge.balance.domain.model

/**
 * The account snapshot carried by a transaction event.
 *
 * [balance] is **not** computed by this service — the authorizer already settled it and ships
 * the resulting balance inside every event. This service projects that snapshot, it does not
 * replay a ledger. See `ProcessedTransaction` for why that distinction matters.
 */
data class Account(
    val id: String,
    val owner: String,
    val createdAt: Long,
    val status: AccountStatus,
    val balance: Money,
) {
    init {
        requireUuid(id, "account.id")
        requireUuid(owner, "account.owner")
        requirePositiveTimestamp(createdAt, "account.created_at")
    }
}
