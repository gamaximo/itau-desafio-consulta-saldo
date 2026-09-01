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
     * O saldo armazenado vem do snapshot da conta, nunca do montante da transação. Este teste
     * fixa isso: um crédito de 97.07 numa conta que reporta 183.12 tem que armazenar 183.12.
     * Armazenar o montante da transação seria a leitura equivocada clássica deste payload.
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
     * A versão precisa vir do timestamp da transação, e não do relógio da máquina, ou a projeção
     * deixaria de ser determinística: reprocessar o tópico produziria versões diferentes a cada
     * vez, e compará-las entre replays não significaria nada.
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
