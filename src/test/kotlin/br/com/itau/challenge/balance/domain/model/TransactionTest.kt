package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import br.com.itau.challenge.balance.fixture.money
import br.com.itau.challenge.balance.fixture.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransactionTest {

    @Test
    fun `builds a valid transaction`() {
        val result = transaction()

        assertEquals(TransactionType.CREDIT, result.type)
        assertEquals(TransactionStatus.APPROVED, result.status)
    }

    @Test
    fun `rejects an id that is not a UUID`() {
        val exception =
            assertFailsWith<InvalidTransactionEventException> {
                transaction(id = "8e8ae808")
            }

        assertTrue(exception.message!!.contains("transaction.id"))
    }

    @Test
    fun `rejects a non-positive timestamp`() {
        listOf(0L, -1L).forEach { invalid ->
            assertFailsWith<InvalidTransactionEventException>("expected $invalid to be rejected") {
                transaction(timestamp = invalid)
            }
        }
    }

    @Test
    fun `rejects a negative amount`() {
        val exception =
            assertFailsWith<InvalidTransactionEventException> {
                transaction(amount = money(amount = "-0.01"))
            }

        assertTrue(exception.message!!.contains("transaction.amount"))
    }

    @Test
    fun `accepts a zero amount`() {
        assertEquals("0.00", transaction(amount = money(amount = "0.00")).amount.amount.toPlainString())
    }
}
