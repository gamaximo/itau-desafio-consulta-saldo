package br.com.itau.challenge.balance.port.input

import br.com.itau.challenge.balance.domain.model.AccountBalance

fun interface GetAccountBalanceUseCase {
    fun getBalance(accountId: String): AccountBalance
}
