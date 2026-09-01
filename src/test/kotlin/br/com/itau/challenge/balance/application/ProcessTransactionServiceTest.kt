package br.com.itau.challenge.balance.application

import br.com.itau.challenge.balance.domain.model.AccountBalance
import br.com.itau.challenge.balance.domain.model.ProcessingOutcome
import br.com.itau.challenge.balance.domain.model.RejectionReason
import br.com.itau.challenge.balance.domain.model.TransactionStatus
import br.com.itau.challenge.balance.fixture.ACCOUNT_ID
import br.com.itau.challenge.balance.fixture.account
import br.com.itau.challenge.balance.fixture.money
import br.com.itau.challenge.balance.fixture.processedTransaction
import br.com.itau.challenge.balance.fixture.transaction
import br.com.itau.challenge.balance.port.output.AccountBalanceRepository
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Relógio fixo bem depois dos timestamps das fixtures, para que nada seja "do futuro". */
private val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

class ProcessTransactionServiceTest {

    /** Registra o que o serviço entregou à porta, para que a projeção possa ser verificada. */
    private class RecordingRepository(
        private val rejection: RejectionReason? = null,
    ) : AccountBalanceRepository {
        var saved: AccountBalance? = null

        override fun saveIfNewer(accountBalance: AccountBalance): RejectionReason? {
            saved = accountBalance
            return rejection
        }
    }

    private fun service(
        repository: AccountBalanceRepository,
        applyDeclined: Boolean = true,
    ) = ProcessTransactionService(repository, applyDeclined, Duration.ofMinutes(5), FIXED_CLOCK)

    @Test
    fun `reporta APPLIED quando a escrita é aceita`() {
        val service = service(RecordingRepository())

        assertEquals(ProcessingOutcome.APPLIED, service.process(processedTransaction()))
    }

    /**
     * Uma escrita condicional rejeitada é um desfecho normal, não uma falha. Se isso algum dia
     * aparecesse como exceção, toda duplicata seria retentada e depois mandada para o DLT.
     */
    @Test
    fun `reporta OUT_OF_ORDER_DISCARDED quando a escrita é rejeitada por ser mais antiga`() {
        val service = service(RecordingRepository(RejectionReason.OUT_OF_ORDER))

        assertEquals(ProcessingOutcome.OUT_OF_ORDER_DISCARDED, service.process(processedTransaction()))
    }

    @Test
    fun `entrega à porta o saldo projetado do snapshot`() {
        val repository = RecordingRepository()
        val service = service(repository, applyDeclined = true)

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
    fun `aplica uma transação recusada quando a flag está ligada`() {
        val repository = RecordingRepository()
        val service = service(repository, applyDeclined = true)

        val outcome = service.process(processedTransaction(transaction = transaction(status = TransactionStatus.DECLINED)))

        assertEquals(ProcessingOutcome.APPLIED, outcome)
        assertNotNull(repository.saved)
    }

    /**
     * Com a flag desligada, uma transação recusada não pode sequer chegar à porta — pular a
     * escrita é o objetivo, não apenas ignorar o resultado dela.
     */
    @Test
    fun `ignora completamente uma transação recusada quando a flag está desligada`() {
        val repository = RecordingRepository()
        val service = service(repository, applyDeclined = false)

        val outcome = service.process(processedTransaction(transaction = transaction(status = TransactionStatus.DECLINED)))

        assertEquals(ProcessingOutcome.DECLINED_SKIPPED, outcome)
        assertNull(repository.saved)
    }

    @Test
    fun `ainda aplica uma transação aprovada quando a flag de recusadas está desligada`() {
        val repository = RecordingRepository()
        val service = service(repository, applyDeclined = false)

        val outcome = service.process(processedTransaction(transaction = transaction(status = TransactionStatus.APPROVED)))

        assertEquals(ProcessingOutcome.APPLIED, outcome)
        assertNotNull(repository.saved)
    }
}
