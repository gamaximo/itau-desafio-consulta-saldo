package br.com.itau.challenge.balance.application

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import br.com.itau.challenge.balance.domain.model.AccountBalance
import br.com.itau.challenge.balance.domain.model.ProcessingOutcome
import br.com.itau.challenge.balance.domain.model.RejectionReason
import br.com.itau.challenge.balance.fixture.processedTransaction
import br.com.itau.challenge.balance.fixture.transaction
import br.com.itau.challenge.balance.port.output.AccountBalanceRepository
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val AGORA: Instant = Instant.parse("2026-01-01T12:00:00Z")
private val RELOGIO: Clock = Clock.fixed(AGORA, ZoneOffset.UTC)
private val TOLERANCIA: Duration = Duration.ofMinutes(5)

private fun Instant.emMicros(): Long = epochSecond * 1_000_000 + nano / 1_000

/**
 * A defesa contra o defeito mais perigoso desta arquitetura.
 *
 * A ordenação por *last-write-wins* confia num timestamp produzido por outro sistema. Um evento
 * com timestamp absurdamente no futuro vence todos os seguintes e **congela a conta**: o saldo
 * para de atualizar em silêncio, sem erro em lugar nenhum, até a data chegar.
 *
 * Não é hipótese — basta o produtor enviar nanossegundos em vez de microssegundos, um erro de
 * unidade comum, e o valor fica mil vezes maior. Sem esta validação, o efeito é permanente e
 * invisível.
 */
class ClockSkewRejectionTest {

    private class RepositorioQueRegistra : AccountBalanceRepository {
        var recebeu: AccountBalance? = null

        override fun saveIfNewer(accountBalance: AccountBalance): RejectionReason? {
            recebeu = accountBalance
            return null
        }
    }

    private val repositorio = RepositorioQueRegistra()
    private val service = ProcessTransactionService(repositorio, true, TOLERANCIA, RELOGIO)

    @Test
    fun `aceita um evento do passado`() {
        val passado = AGORA.minus(Duration.ofDays(30)).emMicros()

        val outcome = service.process(processedTransaction(transaction = transaction(timestamp = passado)))

        assertEquals(ProcessingOutcome.APPLIED, outcome)
    }

    /** Diferença de relógio entre máquinas é normal; dentro da tolerância o evento é legítimo. */
    @Test
    fun `aceita um evento levemente adiantado, dentro da tolerancia`() {
        val quaseNoLimite = AGORA.plus(Duration.ofMinutes(4)).emMicros()

        val outcome = service.process(processedTransaction(transaction = transaction(timestamp = quaseNoLimite)))

        assertEquals(ProcessingOutcome.APPLIED, outcome)
    }

    @Test
    fun `rejeita um evento alem da tolerancia`() {
        val adiantado = AGORA.plus(Duration.ofMinutes(6)).emMicros()

        assertFailsWith<InvalidTransactionEventException> {
            service.process(processedTransaction(transaction = transaction(timestamp = adiantado)))
        }

        assertNull(repositorio.recebeu, "o evento não pode chegar ao banco")
    }

    /**
     * O cenário concreto: o produtor troca microssegundos por nanossegundos e o timestamp fica
     * mil vezes maior, apontando para o ano 58 mil. Antes desta validação, esse único evento
     * congelava a conta para sempre.
     */
    @Test
    fun `rejeita um timestamp em nanossegundos, o erro de unidade classico`() {
        val emNanos = AGORA.emMicros() * 1_000

        val excecao =
            assertFailsWith<InvalidTransactionEventException> {
                service.process(processedTransaction(transaction = transaction(timestamp = emNanos)))
            }

        assertTrue(excecao.message!!.contains("futuro"))
        assertNull(repositorio.recebeu, "um evento assim não pode ser gravado: ele travaria a conta")
    }

    /**
     * A rejeição precisa ser [InvalidTransactionEventException] para que o error handler a trate
     * como não retentável e mande ao dead letter topic. Se fosse outro tipo, o consumidor
     * retentaria quatro vezes um evento que nunca vai melhorar.
     */
    @Test
    fun `a rejeicao e do tipo que vai direto ao dead letter topic`() {
        val adiantado = AGORA.plus(Duration.ofHours(1)).emMicros()

        val excecao =
            assertFailsWith<InvalidTransactionEventException> {
                service.process(processedTransaction(transaction = transaction(timestamp = adiantado)))
            }

        assertTrue(excecao.message!!.contains("microssegundos"), "a mensagem deve sugerir a causa provável")
    }
}
