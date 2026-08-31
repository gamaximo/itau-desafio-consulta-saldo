package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.fixture.TRANSACTION_TIMESTAMP
import br.com.itau.challenge.balance.fixture.accountBalance
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class AccountBalanceTest {

    @Test
    fun `derives updatedAt from the version`() {
        val balance = accountBalance(version = TRANSACTION_TIMESTAMP)

        assertEquals(Instant.parse("2025-07-04T15:02:44.589998Z"), balance.updatedAt)
    }

    /**
     * Two balances with the same version describe the same moment. Since `updatedAt` is derived
     * rather than stored, the two can never disagree — which is the whole reason it is derived.
     */
    @Test
    fun `two balances with the same version report the same instant`() {
        assertEquals(
            accountBalance(version = 1_000_000L).updatedAt,
            accountBalance(version = 1_000_000L, lastTransactionId = "different").updatedAt,
        )
    }
}
