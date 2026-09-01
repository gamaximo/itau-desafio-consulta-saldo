package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import java.math.BigDecimal

/**
 * Uma transação financeira conforme decidida pelo autorizador.
 *
 * [timestamp] é o instante, em **microssegundos** desde o epoch, em que o autorizador liquidou
 * esta transação. É a chave de ordenação de todo o sistema: o Kafka só garante ordem dentro de
 * uma partição, então quem decide qual snapshot é mais recente é o timestamp, não a ordem de
 * chegada.
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
