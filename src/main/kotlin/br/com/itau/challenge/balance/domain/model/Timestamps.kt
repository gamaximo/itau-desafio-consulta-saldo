package br.com.itau.challenge.balance.domain.model

import java.time.Instant

private const val MICROS_PER_SECOND = 1_000_000L
private const val NANOS_PER_MICRO = 1_000L

/**
 * Converts an epoch timestamp in **microseconds** — the unit used across the transaction
 * topic — into an [Instant].
 *
 * Uses floor division so that timestamps before the epoch convert correctly instead of
 * truncating toward zero. `Instant` keeps nanosecond precision, so no digits are lost.
 */
fun microsToInstant(epochMicros: Long): Instant =
    Instant.ofEpochSecond(
        Math.floorDiv(epochMicros, MICROS_PER_SECOND),
        Math.floorMod(epochMicros, MICROS_PER_SECOND) * NANOS_PER_MICRO,
    )
