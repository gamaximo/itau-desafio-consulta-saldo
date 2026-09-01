package br.com.itau.challenge.balance.application

import br.com.itau.challenge.balance.domain.exception.AccountBalanceNotFoundException
import br.com.itau.challenge.balance.fixture.ACCOUNT_ID
import br.com.itau.challenge.balance.fixture.accountBalance
import br.com.itau.challenge.balance.port.output.AccountBalanceProvider
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GetAccountBalanceServiceTest {

    @Test
    fun `returns the stored balance`() {
        val expected = accountBalance()
        val service = GetAccountBalanceService(AccountBalanceProvider { expected })

        assertEquals(expected, service.getBalance(ACCOUNT_ID))
    }

    /**
     * Um saldo ausente vira exceção aqui, em vez de um null subindo pela aplicação. O adaptador
     * web transforma isso num 404; nada entre a porta e a resposta precisa lembrar de checar
     * null.
     */
    @Test
    fun `raises a not-found error when no balance has been projected`() {
        val service = GetAccountBalanceService(AccountBalanceProvider { null })

        val exception = assertFailsWith<AccountBalanceNotFoundException> { service.getBalance(ACCOUNT_ID) }

        assertEquals(ACCOUNT_ID, exception.accountId)
    }

    @Test
    fun `queries the provider with the requested account`() {
        var requested: String? = null
        val service =
            GetAccountBalanceService(
                AccountBalanceProvider { accountId ->
                    requested = accountId
                    accountBalance()
                },
            )

        service.getBalance(ACCOUNT_ID)

        assertEquals(ACCOUNT_ID, requested)
    }
}
