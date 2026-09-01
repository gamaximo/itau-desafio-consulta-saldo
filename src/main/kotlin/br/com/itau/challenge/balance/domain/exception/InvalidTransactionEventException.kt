package br.com.itau.challenge.balance.domain.exception

/**
 * Evento de transação que nunca vai se tornar válido, por mais vezes que seja retentado:
 * identificadores malformados, valores de enum desconhecidos, montantes negativos, código de
 * moeda inválido.
 *
 * Retentar travaria a partição para sempre, então o consumidor manda direto para o dead letter
 * topic em vez de aplicar backoff.
 */
class InvalidTransactionEventException(
    message: String,
) : RuntimeException(message)
