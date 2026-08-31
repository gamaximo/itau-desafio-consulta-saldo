package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import br.com.itau.challenge.balance.fixture.money
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MoneyTest {

    @Test
    fun `accepts a valid ISO 4217 currency`() {
        val value = Money(BigDecimal("183.12"), "BRL")

        assertEquals(BigDecimal("183.12"), value.amount)
        assertEquals("BRL", value.currency)
    }

    @Test
    fun `rejects a code that is not a real ISO 4217 currency`() {
        listOf("brl", "BR", "BRLX", "", "R$1", "ZZZ").forEach { invalid ->
            assertFailsWith<InvalidTransactionEventException>("expected '$invalid' to be rejected") {
                Money(BigDecimal.ONE, invalid)
            }
        }
    }

    /**
     * The reason this class normalises at all: DynamoDB trims trailing zeros, so a balance
     * written as `300.00` comes back as `300`. Both spellings must produce the same Money, or
     * the value would change meaning simply by being stored and read back.
     */
    @Test
    fun `normalises the amount to the currency scale`() {
        assertEquals("300.00", money(amount = "300").amount.toPlainString())
        assertEquals("300.00", money(amount = "300.0").amount.toPlainString())
        assertEquals("300.00", money(amount = "300.00").amount.toPlainString())
    }

    @Test
    fun `treats the same value written with different scales as equal`() {
        assertEquals(money(amount = "300"), money(amount = "300.00"))
        assertEquals(money(amount = "300").hashCode(), money(amount = "300.00").hashCode())
    }

    @Test
    fun `is not equal across currencies`() {
        assertNotEquals(Money(BigDecimal("10.00"), "BRL"), Money(BigDecimal("10.00"), "USD"))
    }

    /**
     * Scale follows the currency, not a hardcoded 2. Yen has no minor unit, so normalising it
     * to two decimals would be wrong in a way that is easy to miss.
     */
    @Test
    fun `uses the fraction digits of the currency itself`() {
        assertEquals("1000", Money(BigDecimal("1000"), "JPY").amount.toPlainString())
        assertEquals("1.500", Money(BigDecimal("1.5"), "BHD").amount.toPlainString())
    }

    /**
     * Rounding money silently is how cents disappear. An amount too precise for its currency is
     * a producer defect, and it is reported as one.
     */
    @Test
    fun `rejects an amount more precise than the currency allows`() {
        val exception = assertFailsWith<InvalidTransactionEventException> { money(amount = "1.234") }

        assertTrue(exception.message!!.contains("precision"))
    }

    /**
     * A balance may legitimately be negative — an overdraft is a normal account state. Only a
     * transaction *amount* is constrained to be non-negative, and that rule lives in
     * [Transaction], not here.
     */
    @Test
    fun `allows a negative amount, because an overdrawn balance is a real balance`() {
        assertEquals(BigDecimal("-42.50"), money(amount = "-42.50").amount)
    }

    @Test
    fun `has a readable toString for logs`() {
        assertTrue(money(amount = "10.00").toString().contains("10.00"))
    }
}
