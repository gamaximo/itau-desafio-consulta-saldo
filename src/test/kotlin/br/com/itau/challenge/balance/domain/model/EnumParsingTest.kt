package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EnumParsingTest {

    @Test
    fun `parses every known transaction type`() {
        assertEquals(TransactionType.CREDIT, TransactionType.from("CREDIT"))
        assertEquals(TransactionType.DEBIT, TransactionType.from("DEBIT"))
    }

    @Test
    fun `parses every known transaction status`() {
        assertEquals(TransactionStatus.APPROVED, TransactionStatus.from("APPROVED"))
        assertEquals(TransactionStatus.DECLINED, TransactionStatus.from("DECLINED"))
    }

    @Test
    fun `parses every known account status`() {
        assertEquals(AccountStatus.ENABLED, AccountStatus.from("ENABLED"))
        assertEquals(AccountStatus.DISABLED, AccountStatus.from("DISABLED"))
    }

    /**
     * The rejection message names the offending value and lists what was expected. When a bad
     * event turns up on the dead letter topic at 3am, that message is the entire diagnosis.
     */
    @Test
    fun `rejects an unknown transaction type and says what was expected`() {
        val exception = assertFailsWith<InvalidTransactionEventException> { TransactionType.from("TRANSFER") }

        assertTrue(exception.message!!.contains("TRANSFER"))
        assertTrue(exception.message!!.contains("CREDIT"))
    }

    @Test
    fun `rejects an unknown transaction status`() {
        assertFailsWith<InvalidTransactionEventException> { TransactionStatus.from("REJECTED") }
    }

    @Test
    fun `rejects an unknown account status`() {
        assertFailsWith<InvalidTransactionEventException> { AccountStatus.from("BLOCKED") }
    }

    /**
     * Case-sensitive on purpose: the contract specifies uppercase values, and quietly accepting
     * `credit` would let a producer drift off-contract without anyone noticing until the
     * mismatch surfaced somewhere less obvious.
     */
    @Test
    fun `rejects a known value in the wrong case`() {
        assertFailsWith<InvalidTransactionEventException> { TransactionType.from("credit") }
    }
}
