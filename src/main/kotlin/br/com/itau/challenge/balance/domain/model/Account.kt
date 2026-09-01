package br.com.itau.challenge.balance.domain.model

/**
 * O snapshot da conta carregado por um evento de transação.
 *
 * [balance] **não** é calculado por este serviço — o autorizador já liquidou a transação e envia
 * o saldo resultante dentro de cada evento. Este serviço projeta esse snapshot, não reprocessa
 * um ledger. Veja `ProcessedTransaction` para entender por que essa distinção importa.
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
