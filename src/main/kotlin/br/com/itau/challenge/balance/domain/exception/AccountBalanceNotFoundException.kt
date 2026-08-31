package br.com.itau.challenge.balance.domain.exception

/**
 * No balance has been projected for this account yet — either the account does not exist,
 * or no transaction for it has been consumed so far. The API cannot tell those apart, since
 * the account lifecycle is owned by the authorizer, not by this service.
 */
class AccountBalanceNotFoundException(
    val accountId: String,
) : RuntimeException("No balance found for account '$accountId'")
