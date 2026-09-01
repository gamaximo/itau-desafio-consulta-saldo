package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import br.com.itau.challenge.balance.fixture.ACCOUNT_ID
import br.com.itau.challenge.balance.fixture.account
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AccountTest {

    @Test
    fun `constrói uma conta válida`() {
        assertEquals(ACCOUNT_ID, account().id)
        assertEquals(AccountStatus.ENABLED, account().status)
    }

    @Test
    fun `rejeita um id que não é UUID`() {
        val exception = assertFailsWith<InvalidTransactionEventException> { account(id = "not-a-uuid") }

        assertTrue(exception.message!!.contains("account.id"))
    }

    @Test
    fun `rejeita um titular que não é UUID`() {
        val exception = assertFailsWith<InvalidTransactionEventException> { account(owner = "12345") }

        assertTrue(exception.message!!.contains("account.owner"))
    }

    @Test
    fun `rejeita um timestamp de criação não positivo`() {
        val exception = assertFailsWith<InvalidTransactionEventException> { account(createdAt = 0) }

        assertTrue(exception.message!!.contains("account.created_at"))
    }

    /**
     * Uma conta DISABLED continua tendo saldo, e o último saldo de uma conta encerrada é
     * exatamente o que o suporte precisa ver. O status é carregado, nunca usado para filtrar
     * eventos.
     */
    @Test
    fun `aceita uma conta desabilitada`() {
        assertEquals(AccountStatus.DISABLED, account(status = AccountStatus.DISABLED).status)
    }
}
