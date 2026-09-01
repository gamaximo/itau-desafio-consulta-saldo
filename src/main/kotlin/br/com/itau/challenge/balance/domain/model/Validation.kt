package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import java.util.UUID

/**
 * Validações compartilhadas pelos modelos de domínio. Todas lançam
 * [InvalidTransactionEventException] para que qualquer rejeição — venha de onde vier no modelo —
 * carregue o mesmo significado para o consumidor: este payload é inprocessável, não retente.
 */

internal fun requireUuid(
    value: String,
    field: String,
): String {
    try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw InvalidTransactionEventException("$field precisa ser um UUID válido, recebido '$value'")
    }
    return value
}

internal fun requirePositiveTimestamp(
    value: Long,
    field: String,
): Long {
    if (value <= 0) {
        throw InvalidTransactionEventException("$field precisa ser um timestamp epoch positivo em microssegundos, recebido $value")
    }
    return value
}
