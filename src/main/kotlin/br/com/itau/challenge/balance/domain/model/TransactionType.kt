package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException

enum class TransactionType {
    CREDIT,
    DEBIT,
    ;

    companion object {
        fun from(raw: String): TransactionType =
            entries.firstOrNull { it.name == raw }
                ?: throw InvalidTransactionEventException(
                    "Unknown transaction type '$raw', expected one of ${entries.joinToString()}",
                )
    }
}
