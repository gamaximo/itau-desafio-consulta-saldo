package br.com.itau.challenge.balance.adapter.input.kafka

import br.com.itau.challenge.balance.domain.model.ProcessingOutcome
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TransactionProcessingMetricsTest {

    private val registry = SimpleMeterRegistry()
    private val metrics = TransactionProcessingMetrics(registry)

    private fun countFor(outcome: ProcessingOutcome) =
        registry.get("balance.transactions.processed").tag("outcome", outcome.name.lowercase()).counter().count()

    @Test
    fun `conta cada resultado sob a própria tag`() {
        metrics.recordOutcome(ProcessingOutcome.APPLIED)
        metrics.recordOutcome(ProcessingOutcome.APPLIED)
        metrics.recordOutcome(ProcessingOutcome.STALE_DISCARDED)

        assertEquals(2.0, countFor(ProcessingOutcome.APPLIED))
        assertEquals(1.0, countFor(ProcessingOutcome.STALE_DISCARDED))
        assertEquals(0.0, countFor(ProcessingOutcome.DECLINED_SKIPPED))
    }

    /**
     * Todos os resultados são registrados na inicialização, e não preguiçosamente na primeira
     * ocorrência. Um contador que só aparece depois de disparar é uma armadilha para alertas:
     * `rate(...) == 0` e "a série não existe" são condições diferentes, e um dashboard montado
     * sobre a série ausente mostra nada em silêncio, em vez de mostrar zero.
     */
    @Test
    fun `registra um contador para cada resultado antes de qualquer evento chegar`() {
        ProcessingOutcome.entries.forEach { outcome ->
            assertEquals(0.0, countFor(outcome), "expected a pre-registered counter for $outcome")
        }
    }

    @Test
    fun `conta os eventos rejeitados`() {
        metrics.recordRejected()

        assertEquals(1.0, registry.get("balance.transactions.rejected").counter().count())
    }
}
