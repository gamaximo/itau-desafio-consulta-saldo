package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EnumParsingTest {

    @Test
    fun `interpreta todos os tipos de transação conhecidos`() {
        assertEquals(TransactionType.CREDIT, TransactionType.from("CREDIT"))
        assertEquals(TransactionType.DEBIT, TransactionType.from("DEBIT"))
    }

    @Test
    fun `interpreta todos os status de transação conhecidos`() {
        assertEquals(TransactionStatus.APPROVED, TransactionStatus.from("APPROVED"))
        assertEquals(TransactionStatus.DECLINED, TransactionStatus.from("DECLINED"))
    }

    @Test
    fun `interpreta todos os status de conta conhecidos`() {
        assertEquals(AccountStatus.ENABLED, AccountStatus.from("ENABLED"))
        assertEquals(AccountStatus.DISABLED, AccountStatus.from("DISABLED"))
    }

    /**
     * A mensagem de rejeição nomeia o valor problemático e lista o que era esperado. Quando um
     * evento ruim aparece no dead letter topic às 3 da manhã, essa mensagem é o diagnóstico
     * inteiro.
     */
    @Test
    fun `rejeita um tipo de transação desconhecido e informa o que era esperado`() {
        val exception = assertFailsWith<InvalidTransactionEventException> { TransactionType.from("TRANSFER") }

        assertTrue(exception.message!!.contains("TRANSFER"))
        assertTrue(exception.message!!.contains("CREDIT"))
    }

    @Test
    fun `rejeita um status de transação desconhecido`() {
        assertFailsWith<InvalidTransactionEventException> { TransactionStatus.from("REJECTED") }
    }

    @Test
    fun `rejeita um status de conta desconhecido`() {
        assertFailsWith<InvalidTransactionEventException> { AccountStatus.from("BLOCKED") }
    }

    /**
     * Sensível a maiúsculas de propósito: o contrato especifica valores em caixa alta, e aceitar
     * `credit` em silêncio deixaria um produtor se afastar do contrato sem ninguém perceber, até
     * a divergência aparecer em algum lugar bem menos óbvio.
     */
    @Test
    fun `rejeita um valor conhecido na caixa errada`() {
        assertFailsWith<InvalidTransactionEventException> { TransactionType.from("credit") }
    }
}
