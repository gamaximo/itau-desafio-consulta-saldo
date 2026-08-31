package br.com.itau.challenge.balance.port.output

import br.com.itau.challenge.balance.domain.model.AccountBalance

fun interface AccountBalanceProvider {
    /** @return the current balance for [accountId], or `null` if none has been projected yet. */
    fun findByAccountId(accountId: String): AccountBalance?
}
