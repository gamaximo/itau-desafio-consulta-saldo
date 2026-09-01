package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.fixture.TRANSACTION_TIMESTAMP
import br.com.itau.challenge.balance.fixture.accountBalance
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class AccountBalanceTest {

    @Test
    fun `deriva updatedAt a partir da versão`() {
        val balance = accountBalance(version = TRANSACTION_TIMESTAMP)

        assertEquals(Instant.parse("2025-07-04T15:02:44.589998Z"), balance.updatedAt)
    }

    /**
     * Dois saldos com a mesma versão descrevem o mesmo momento. Como `updatedAt` é derivado, e
     * não armazenado, os dois nunca podem discordar — que é justamente a razão de ser derivado.
     */
    @Test
    fun `dois saldos com a mesma versão reportam o mesmo instante`() {
        assertEquals(
            accountBalance(version = 1_000_000L).updatedAt,
            accountBalance(version = 1_000_000L, lastTransactionId = "different").updatedAt,
        )
    }
}
