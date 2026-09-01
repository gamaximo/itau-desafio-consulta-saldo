package br.com.itau.challenge.balance.port.output

import br.com.itau.challenge.balance.domain.model.AccountBalance

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
     * @return `true` se o saldo foi aplicado, `false` se uma versão mais recente ou idêntica já
     *   estava armazenada — o que é o resultado normal para duplicatas e entregas atrasadas, e
     *   não um erro.
     */
    fun saveIfNewer(accountBalance: AccountBalance): Boolean
}
