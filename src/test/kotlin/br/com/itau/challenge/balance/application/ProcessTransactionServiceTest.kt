package br.com.itau.challenge.balance.application

import br.com.itau.challenge.balance.domain.model.AccountBalance
import br.com.itau.challenge.balance.domain.model.ProcessingOutcome
import br.com.itau.challenge.balance.domain.model.TransactionStatus
import br.com.itau.challenge.balance.fixture.ACCOUNT_ID
import br.com.itau.challenge.balance.fixture.account
import br.com.itau.challenge.balance.fixture.money
import br.com.itau.challenge.balance.fixture.processedTransaction
import br.com.itau.challenge.balance.fixture.transaction
import br.com.itau.challenge.balance.port.output.AccountBalanceRepository
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProcessTransactionServiceTest {

    /** Registra o que o serviço entregou à porta, para que a projeção possa ser verificada. */
    private class RecordingRepository(
        private val accepted: Boolean,
    ) : AccountBalanceRepository {
        var saved: AccountBalance? = null

        override fun saveIfNewer(accountBalance: AccountBalance): Boolean {
            saved = accountBalance
            return accepted
        }
    }

    @Test
    fun `reports APPLIED when the write is accepted`() {
        val service = ProcessTransactionService(RecordingRepository(accepted = true), applyDeclinedTransactions = true)

        assertEquals(ProcessingOutcome.APPLIED, service.process(processedTransaction()))
    }

    /**
     * Uma escrita condicional rejeitada é um desfecho normal, não uma falha. Se isso algum dia
     * aparecesse como exceção, toda duplicata seria retentada e depois mandada para o DLT.
     */
    @Test
    fun `reports STALE_DISCARDED when the write is rejected as not newer`() {
        val service = ProcessTransactionService(RecordingRepository(accepted = false), applyDeclinedTransactions = true)

        assertEquals(ProcessingOutcome.STALE_DISCARDED, service.process(processedTransaction()))
    }

    @Test
    fun `hands the port the projected snapshot balance`() {
        val repository = RecordingRepository(accepted = true)
        val service = ProcessTransactionService(repository, applyDeclinedTransactions = true)

        service.process(
            processedTransaction(
                transaction = transaction(amount = money(amount = "97.07")),
                account = account(balance = money(amount = "183.12")),
            ),
        )

        val saved = assertNotNull(repository.saved)
        assertEquals(ACCOUNT_ID, saved.accountId)
        assertEquals(BigDecimal("183.12"), saved.balance.amount)
    }

    @Test
    fun `applies a declined transaction when the flag is on`() {
        val repository = RecordingRepository(accepted = true)
        val service = ProcessTransactionService(repository, applyDeclinedTransactions = true)

        val outcome = service.process(processedTransaction(transaction = transaction(status = TransactionStatus.DECLINED)))

        assertEquals(ProcessingOutcome.APPLIED, outcome)
        assertNotNull(repository.saved)
    }

    /**
     * Com a flag desligada, uma transação recusada não pode sequer chegar à porta — pular a
     * escrita é o objetivo, não apenas ignorar o resultado dela.
     */
    @Test
    fun `skips a declined transaction entirely when the flag is off`() {
        val repository = RecordingRepository(accepted = true)
        val service = ProcessTransactionService(repository, applyDeclinedTransactions = false)

        val outcome = service.process(processedTransaction(transaction = transaction(status = TransactionStatus.DECLINED)))

        assertEquals(ProcessingOutcome.DECLINED_SKIPPED, outcome)
        assertNull(repository.saved)
    }

    @Test
    fun `still applies an approved transaction when the declined flag is off`() {
        val repository = RecordingRepository(accepted = true)
        val service = ProcessTransactionService(repository, applyDeclinedTransactions = false)

        val outcome = service.process(processedTransaction(transaction = transaction(status = TransactionStatus.APPROVED)))

        assertEquals(ProcessingOutcome.APPLIED, outcome)
        assertNotNull(repository.saved)
    }
}
