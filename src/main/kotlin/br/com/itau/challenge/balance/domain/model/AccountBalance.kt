package br.com.itau.challenge.balance.domain.model

import java.time.Instant

/**
 * O estado projetado do saldo de uma conta — um item por conta no DynamoDB, e o payload por trás
 * de `GET /balances/{accountId}`.
 *
 * [version] é o timestamp, em microssegundos, da transação que produziu este snapshot. Ele é
 * carregado explicitamente porque é o que torna a escrita segura sob concorrência: o repositório
 * só sobrescreve um item cuja versão armazenada seja estritamente mais antiga, de modo que um
 * evento atrasado ou duplicado nunca faça o saldo retroceder.
 */
data class AccountBalance(
    val accountId: String,
    val owner: String,
    val balance: Money,
    val lastTransactionId: String,
    val version: Long,
) {
    /**
     * Quando este saldo passou a valer — derivado de [version] em vez de armazenado ao lado dela.
     *
     * São o mesmo instante expresso em duas unidades, e manter as duas como fonte de verdade
     * seria um convite a divergirem. O item persistido carrega uma cópia legível por humanos, mas
     * ela é escrita para operadores lerem e nunca é lida de volta pela aplicação.
     */
    val updatedAt: Instant
        get() = microsToInstant(version)
}
