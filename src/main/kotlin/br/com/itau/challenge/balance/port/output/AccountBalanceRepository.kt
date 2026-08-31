package br.com.itau.challenge.balance.port.output

import br.com.itau.challenge.balance.domain.model.AccountBalance

fun interface AccountBalanceRepository {
    /**
     * Stores [accountBalance] only if it is strictly newer than what is already stored for the
     * same account.
     *
     * The comparison lives behind this port on purpose: making it atomic is the adapter's
     * problem (a conditional write, a compare-and-swap, an optimistic lock — whatever the
     * backing store offers). A read-then-write in the use case would be a race, since two
     * consumers on different partitions can process the same account concurrently.
     *
     * @return `true` if the balance was applied, `false` if a newer or identical version was
     *   already stored — which is the normal outcome for duplicates and late deliveries, not
     *   an error.
     */
    fun saveIfNewer(accountBalance: AccountBalance): Boolean
}
