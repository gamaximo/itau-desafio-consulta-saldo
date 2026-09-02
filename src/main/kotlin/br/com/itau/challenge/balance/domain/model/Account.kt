package br.com.itau.challenge.balance.domain.model

/**
 * O snapshot da conta carregado por um evento de transação.
 *
 * [balance] **não** é calculado por este serviço — o autorizador já liquidou a transação e envia o
 * saldo resultante dentro de cada evento. Este serviço projeta esse snapshot, não reprocessa um
 * ledger.
 *
 * Não é `data class` porque [id] e [owner] guardam a forma **canônica** do identificador, e não o
 * texto recebido: um `data class` exporia os valores crus via construtor e `copy`, e bastaria isso
 * para um `accountId` em maiúsculas ser gravado numa grafia que a consulta nunca encontraria.
 */
class Account(
    id: String,
    owner: String,
    val createdAt: Long,
    val status: AccountStatus,
    val balance: Money,
) {
    val id: String = requireUuid(id, "account.id")
    val owner: String = requireUuid(owner, "account.owner")

    init {
        requirePositiveTimestamp(createdAt, "account.created_at")
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is Account &&
                    id == other.id &&
                    owner == other.owner &&
                    createdAt == other.createdAt &&
                    status == other.status &&
                    balance == other.balance
            )

    override fun hashCode(): Int =
        listOf(id, owner, createdAt, status, balance).fold(1) { acc, field -> 31 * acc + field.hashCode() }

    override fun toString(): String =
        "Account(id='$id', owner='$owner', createdAt=$createdAt, status=$status, balance=$balance)"
}
