package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException

enum class TransactionStatus {
    APPROVED,
    DECLINED,
    ;

    companion object {
        fun from(raw: String): TransactionStatus =
            entries.firstOrNull { it.name == raw }
                ?: throw InvalidTransactionEventException(
                    "Unknown transaction status '$raw', expected one of ${entries.joinToString()}",
                )
    }
}
