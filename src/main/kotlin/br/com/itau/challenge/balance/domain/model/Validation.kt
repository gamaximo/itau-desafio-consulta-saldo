package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import java.util.UUID

/**
 * Validações compartilhadas pelos modelos de domínio. Todas lançam
 * [InvalidTransactionEventException] para que qualquer rejeição — venha de onde vier no modelo —
 * carregue o mesmo significado para o consumidor: este payload é inprocessável, não retente.
 */

/**
 * Valida **e normaliza** o identificador para a forma canônica.
 *
 * A normalização não é cosmética: `accountId` é a partition key, e a API converte o path para
 * `UUID` antes de consultar, o que sempre produz minúsculas e sem abreviações. Guardar a string
 * exatamente como veio no payload significaria que um evento com `BCCECBE8-…` seria gravado em
 * maiúsculas e **nunca mais encontrado** — a consulta procuraria `bccecbe8-…` e devolveria 404
 * para um saldo que existe.
 *
 * O mesmo vale para as formas abreviadas que `UUID.fromString` aceita: `1-1-1-1-1` é um UUID
 * válido para o Java, e expande para `00000001-0001-0001-0001-000000000001`. Sem normalizar,
 * escrita e leitura usariam grafias diferentes da mesma chave.
 */
internal fun requireUuid(
    value: String,
    field: String,
): String =
    try {
        UUID.fromString(value).toString()
    } catch (_: IllegalArgumentException) {
        throw InvalidTransactionEventException("$field precisa ser um UUID válido, recebido '$value'")
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
