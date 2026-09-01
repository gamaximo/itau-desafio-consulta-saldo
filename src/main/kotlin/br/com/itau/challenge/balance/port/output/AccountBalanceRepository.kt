package br.com.itau.challenge.balance.port.output

import br.com.itau.challenge.balance.domain.model.AccountBalance
import br.com.itau.challenge.balance.domain.model.RejectionReason

fun interface AccountBalanceRepository {
    /**
     * Armazena [accountBalance] somente se ele for estritamente mais recente do que o que já está
     * armazenado para a mesma conta.
     *
     * A comparação fica atrás desta porta de propósito: torná-la atômica é problema do adaptador
     * (uma escrita condicional, um compare-and-swap, um lock otimista — o que o armazenamento
     * oferecer). Um read-then-write no caso de uso seria uma condição de corrida, já que dois
     * consumidores em partições diferentes podem processar a mesma conta simultaneamente.
     *
     * @return `null` se o saldo foi aplicado, ou o motivo da recusa — que é um desfecho normal
     *   para duplicatas e entregas atrasadas, não um erro.
     */
    fun saveIfNewer(accountBalance: AccountBalance): RejectionReason?
}
