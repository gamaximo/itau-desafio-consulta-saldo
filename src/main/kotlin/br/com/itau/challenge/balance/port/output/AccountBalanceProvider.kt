package br.com.itau.challenge.balance.port.output

import br.com.itau.challenge.balance.domain.model.AccountBalance

fun interface AccountBalanceProvider {
    /** @return o saldo atual de [accountId], ou `null` se nenhum foi projetado ainda. */
    fun findByAccountId(accountId: String): AccountBalance?
}
