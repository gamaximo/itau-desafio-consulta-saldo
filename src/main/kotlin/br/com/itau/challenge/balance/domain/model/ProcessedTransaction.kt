package br.com.itau.challenge.balance.domain.model

/**
 * A settled transaction together with the account snapshot it produced — the full event
 * published on `transacoes-financeiras-processadas`.
 *
 * ### Why this service projects instead of accumulating
 *
 * The authorizer ships `account.balance` already settled. Recomputing the balance here by
 * adding credits and subtracting debits would be strictly worse: it would require every event
 * to arrive exactly once and in order — neither of which Kafka guarantees across partitions —
 * and any gap would silently corrupt the balance forever. Projecting the authoritative
 * snapshot instead makes each event self-sufficient, which is what allows duplicates and
 * out-of-order delivery to be resolved by simply comparing versions.
 */
data class ProcessedTransaction(
    val transaction: Transaction,
    val account: Account,
) {
    /**
     * Projects this event into the balance state to be persisted.
     *
     * The version is the transaction timestamp, which keeps the projection deterministic:
     * reprocessing the topic from offset zero produces byte-identical items, no matter when
     * the replay happens.
     */
    fun toAccountBalance(): AccountBalance =
        AccountBalance(
            accountId = account.id,
            owner = account.owner,
            balance = account.balance,
            lastTransactionId = transaction.id,
            version = transaction.timestamp,
        )
}
