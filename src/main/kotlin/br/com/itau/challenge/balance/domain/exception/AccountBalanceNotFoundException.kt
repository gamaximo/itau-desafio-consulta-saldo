package br.com.itau.challenge.balance.domain.exception

/**
 * Nenhum saldo foi projetado para esta conta ainda — ou a conta não existe, ou nenhuma
 * transação dela foi consumida até agora. A API não consegue distinguir os dois casos, já que o
 * ciclo de vida da conta pertence ao autorizador, não a este serviço.
 */
class AccountBalanceNotFoundException(
    val accountId: String,
) : RuntimeException("Nenhum saldo encontrado para a conta '$accountId'")
