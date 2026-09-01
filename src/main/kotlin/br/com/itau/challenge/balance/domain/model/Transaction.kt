package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import java.math.BigDecimal

/**
 * Uma transação financeira conforme decidida pelo autorizador.
 *
 * [timestamp] é o instante, em **microssegundos** desde o epoch, em que o autorizador liquidou
 * esta transação. É a chave de ordenação de todo o sistema: o Kafka só garante ordem dentro de uma
 * partição, então quem decide qual snapshot é mais recente é o timestamp, não a ordem de chegada.
 *
 * Não é `data class` pelo mesmo motivo de [Account]: [id] guarda a forma canônica do
 * identificador, e um `data class` deixaria o valor cru vazar por construtor e `copy`.
 */
class Transaction(
    id: String,
    val type: TransactionType,
    val amount: Money,
    val status: TransactionStatus,
    val timestamp: Long,
) {
    val id: String = requireUuid(id, "transaction.id")

    init {
        requirePositiveTimestamp(timestamp, "transaction.timestamp")
        if (amount.amount < BigDecimal.ZERO) {
            throw InvalidTransactionEventException("transaction.amount não pode ser negativo, recebido ${amount.amount}")
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is Transaction &&
                    id == other.id &&
                    type == other.type &&
                    amount == other.amount &&
                    status == other.status &&
                    timestamp == other.timestamp
            )

    override fun hashCode(): Int =
        listOf(id, type, amount, status, timestamp).fold(1) { acc, field -> 31 * acc + field.hashCode() }

    override fun toString(): String =
        "Transaction(id='$id', type=$type, amount=$amount, status=$status, timestamp=$timestamp)"
}
