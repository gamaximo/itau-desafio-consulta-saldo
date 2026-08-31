package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import java.util.UUID

/**
 * Validation helpers shared by the domain models. They throw [InvalidTransactionEventException]
 * so that every rejection — wherever it happens in the model — carries the same meaning to the
 * consumer: this payload is unprocessable, do not retry it.
 */

internal fun requireUuid(
    value: String,
    field: String,
): String {
    try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw InvalidTransactionEventException("$field must be a valid UUID, got '$value'")
    }
    return value
}

internal fun requirePositiveTimestamp(
    value: Long,
    field: String,
): Long {
    if (value <= 0) {
        throw InvalidTransactionEventException("$field must be a positive epoch timestamp in microseconds, got $value")
    }
    return value
}
