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
    fun `aceita uma moeda ISO 4217 válida`() {
        val value = Money(BigDecimal("183.12"), "BRL")

        assertEquals(BigDecimal("183.12"), value.amount)
        assertEquals("BRL", value.currency)
    }

    @Test
    fun `rejeita um código que não é uma moeda ISO 4217 real`() {
        listOf("brl", "BR", "BRLX", "", "R$1", "ZZZ").forEach { invalid ->
            assertFailsWith<InvalidTransactionEventException>("expected '$invalid' to be rejected") {
                Money(BigDecimal.ONE, invalid)
            }
        }
    }

    /**
     * O motivo de esta classe normalizar: o DynamoDB corta zeros à direita, então um saldo
     * gravado como `300.00` volta como `300`. As duas grafias precisam produzir o mesmo Money, ou
     * o valor mudaria de significado só por ter sido armazenado e lido de volta.
     */
    @Test
    fun `normaliza o valor para a escala da moeda`() {
        assertEquals("300.00", money(amount = "300").amount.toPlainString())
        assertEquals("300.00", money(amount = "300.0").amount.toPlainString())
        assertEquals("300.00", money(amount = "300.00").amount.toPlainString())
    }

    @Test
    fun `trata como iguais o mesmo valor escrito com escalas diferentes`() {
        assertEquals(money(amount = "300"), money(amount = "300.00"))
        assertEquals(money(amount = "300").hashCode(), money(amount = "300.00").hashCode())
    }

    @Test
    fun `não considera iguais valores de moedas diferentes`() {
        assertNotEquals(Money(BigDecimal("10.00"), "BRL"), Money(BigDecimal("10.00"), "USD"))
    }

    /**
     * A escala segue a moeda, e não um 2 fixo no código. O iene não tem subunidade, então
     * normalizá-lo para duas casas decimais estaria errado de um jeito fácil de passar batido.
     */
    @Test
    fun `usa os dígitos fracionários da própria moeda`() {
        assertEquals("1000", Money(BigDecimal("1000"), "JPY").amount.toPlainString())
        assertEquals("1.500", Money(BigDecimal("1.5"), "BHD").amount.toPlainString())
    }

    /**
     * Arredondar dinheiro em silêncio é como centavos somem. Um montante preciso demais para a
     * moeda dele é um defeito do produtor, e é reportado como tal.
     */
    @Test
    fun `rejeita um valor mais preciso do que a moeda permite`() {
        val exception = assertFailsWith<InvalidTransactionEventException> { money(amount = "1.234") }

        assertTrue(exception.message!!.contains("precision"))
    }

    /**
     * Um saldo pode legitimamente ser negativo — cheque especial é um estado normal de conta.
     * Apenas o *montante* de uma transação é obrigado a ser não-negativo, e essa regra vive em
     * [Transaction], não aqui.
     */
    @Test
    fun `permite valor negativo, porque saldo em cheque especial é um saldo real`() {
        assertEquals(BigDecimal("-42.50"), money(amount = "-42.50").amount)
    }

    @Test
    fun `tem um toString legível para os logs`() {
        assertTrue(money(amount = "10.00").toString().contains("10.00"))
    }
}
