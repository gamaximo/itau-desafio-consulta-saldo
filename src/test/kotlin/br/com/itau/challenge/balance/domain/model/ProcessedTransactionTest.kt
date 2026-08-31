package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.fixture.ACCOUNT_ID
import br.com.itau.challenge.balance.fixture.OWNER_ID
import br.com.itau.challenge.balance.fixture.TRANSACTION_ID
import br.com.itau.challenge.balance.fixture.TRANSACTION_TIMESTAMP
import br.com.itau.challenge.balance.fixture.account
import br.com.itau.challenge.balance.fixture.money
import br.com.itau.challenge.balance.fixture.processedTransaction
import br.com.itau.challenge.balance.fixture.transaction
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

class ProcessedTransactionTest {

    @Test
    fun `projects the event into a balance`() {
        val result = processedTransaction().toAccountBalance()

        assertEquals(ACCOUNT_ID, result.accountId)
        assertEquals(OWNER_ID, result.owner)
        assertEquals(TRANSACTION_ID, result.lastTransactionId)
        assertEquals(TRANSACTION_TIMESTAMP, result.version)
    }

    /**
     * The stored balance comes from the account snapshot, never from the transaction amount.
     * This test pins that: a 97.07 credit on an account reporting 183.12 must store 183.12.
     * Storing the transaction amount would be the classic misreading of this payload.
     */
    @Test
    fun `stores the account snapshot balance, not the transaction amount`() {
        val event =
            processedTransaction(
                transaction = transaction(amount = money(amount = "97.07")),
                account = account(balance = money(amount = "183.12")),
            )

        assertEquals(BigDecimal("183.12"), event.toAccountBalance().balance.amount)
    }

    /**
     * The version must come from the transaction timestamp and not from the wall clock, or the
     * projection would stop being deterministic: replaying the topic would produce different
     * versions each time, and comparing them across a replay would be meaningless.
     */
    @Test
    fun `takes its version from the transaction timestamp`() {
        val event = processedTransaction(transaction = transaction(timestamp = 1_700_000_000_000_000L))

        assertEquals(1_700_000_000_000_000L, event.toAccountBalance().version)
    }

    @Test
    fun `projects the same event to the same balance every time`() {
        val event = processedTransaction()

        assertEquals(event.toAccountBalance(), event.toAccountBalance())
    }
}
