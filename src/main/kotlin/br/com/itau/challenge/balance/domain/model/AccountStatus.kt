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
                    "Status de conta desconhecido '$raw', esperado um de ${entries.joinToString()}",
                )
    }
}
