package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import br.com.itau.challenge.balance.fixture.money
import br.com.itau.challenge.balance.fixture.transaction
import br.com.itau.challenge.balance.fixture.TRANSACTION_ID
import br.com.itau.challenge.balance.fixture.TRANSACTION_TIMESTAMP
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TransactionTest {

    @Test
    fun `constrói uma transação válida`() {
        val result = transaction()

        assertEquals(TransactionType.CREDIT, result.type)
        assertEquals(TransactionStatus.APPROVED, result.status)
    }

    @Test
    fun `rejeita um id que não é UUID`() {
        val exception =
            assertFailsWith<InvalidTransactionEventException> {
                transaction(id = "8e8ae808")
            }

        assertTrue(exception.message!!.contains("transaction.id"))
    }

    @Test
    fun `rejeita um timestamp não positivo`() {
        listOf(0L, -1L).forEach { invalid ->
            assertFailsWith<InvalidTransactionEventException>("expected $invalid to be rejected") {
                transaction(timestamp = invalid)
            }
        }
    }

    @Test
    fun `rejeita um valor negativo`() {
        val exception =
            assertFailsWith<InvalidTransactionEventException> {
                transaction(amount = money(amount = "-0.01"))
            }

        assertTrue(exception.message!!.contains("transaction.amount"))
    }

    @Test
    fun `aceita valor zero`() {
        assertEquals("0.00", transaction(amount = money(amount = "0.00")).amount.amount.toPlainString())
    }

    @Test
    fun `distingue transações diferentes e reconhece a si mesma`() {
        val tx = transaction()

        assertEquals(tx, tx)
        assertNotEquals(tx, transaction(timestamp = TRANSACTION_TIMESTAMP + 1))
        assertNotEquals(tx, transaction(type = TransactionType.DEBIT))
        assertNotEquals(tx, transaction(status = TransactionStatus.DECLINED))
        assertNotEquals<Any?>(tx, "não é uma transação")
        assertNotEquals<Any?>(tx, null)
        assertEquals(tx.hashCode(), transaction().hashCode())
    }

    @Test
    fun `tem um toString legível para os logs`() {
        assertTrue(transaction().toString().contains(TRANSACTION_ID))
    }
}
