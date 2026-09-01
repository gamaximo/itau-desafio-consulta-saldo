package br.com.itau.challenge.balance.domain.model

import java.time.Instant

private const val MICROS_PER_SECOND = 1_000_000L
private const val NANOS_PER_MICRO = 1_000L

/**
 * Converte um timestamp epoch em **microssegundos** — a unidade usada em todo o tópico de
 * transações — para um [Instant].
 *
 * Usa divisão com piso (floor) para que timestamps anteriores ao epoch sejam convertidos
 * corretamente, em vez de truncarem em direção a zero. `Instant` mantém precisão de
 * nanossegundos, então nenhum dígito é perdido.
 */
fun microsToInstant(epochMicros: Long): Instant =
    Instant.ofEpochSecond(
        Math.floorDiv(epochMicros, MICROS_PER_SECOND),
        Math.floorMod(epochMicros, MICROS_PER_SECOND) * NANOS_PER_MICRO,
    )
