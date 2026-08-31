package br.com.itau.challenge.balance.domain.exception

/**
 * A transaction event that can never become valid, no matter how many times it is retried:
 * malformed identifiers, unknown enum values, negative amounts, bad currency codes.
 *
 * Retrying it would block the partition forever, so the consumer routes it straight to the
 * dead letter topic instead of backing off.
 */
class InvalidTransactionEventException(
    message: String,
) : RuntimeException(message)
