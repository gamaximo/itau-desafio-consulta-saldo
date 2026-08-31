package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException

enum class AccountStatus {
    ENABLED,
    DISABLED,
    ;

    companion object {
        fun from(raw: String): AccountStatus =
            entries.firstOrNull { it.name == raw }
                ?: throw InvalidTransactionEventException(
                    "Unknown account status '$raw', expected one of ${entries.joinToString()}",
                )
    }
}
