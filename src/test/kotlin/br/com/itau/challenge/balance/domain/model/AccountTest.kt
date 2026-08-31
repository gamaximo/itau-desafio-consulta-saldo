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
    fun `builds a valid account`() {
        assertEquals(ACCOUNT_ID, account().id)
        assertEquals(AccountStatus.ENABLED, account().status)
    }

    @Test
    fun `rejects an id that is not a UUID`() {
        val exception = assertFailsWith<InvalidTransactionEventException> { account(id = "not-a-uuid") }

        assertTrue(exception.message!!.contains("account.id"))
    }

    @Test
    fun `rejects an owner that is not a UUID`() {
        val exception = assertFailsWith<InvalidTransactionEventException> { account(owner = "12345") }

        assertTrue(exception.message!!.contains("account.owner"))
    }

    @Test
    fun `rejects a non-positive creation timestamp`() {
        val exception = assertFailsWith<InvalidTransactionEventException> { account(createdAt = 0) }

        assertTrue(exception.message!!.contains("account.created_at"))
    }

    /**
     * A DISABLED account still has a balance, and a closed account's last balance is exactly
     * what support needs to see. Status is carried, never used to filter events out.
     */
    @Test
    fun `accepts a disabled account`() {
        assertEquals(AccountStatus.DISABLED, account(status = AccountStatus.DISABLED).status)
    }
}
