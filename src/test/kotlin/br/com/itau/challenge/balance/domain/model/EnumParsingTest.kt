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
     * A mensagem de rejeição nomeia o valor problemático e lista o que era esperado. Quando um
     * evento ruim aparece no dead letter topic às 3 da manhã, essa mensagem é o diagnóstico
     * inteiro.
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
     * Sensível a maiúsculas de propósito: o contrato especifica valores em caixa alta, e aceitar
     * `credit` em silêncio deixaria um produtor se afastar do contrato sem ninguém perceber, até
     * a divergência aparecer em algum lugar bem menos óbvio.
     */
    @Test
    fun `rejects a known value in the wrong case`() {
        assertFailsWith<InvalidTransactionEventException> { TransactionType.from("credit") }
    }
}
