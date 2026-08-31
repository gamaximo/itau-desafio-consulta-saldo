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
    fun `counts each outcome under its own tag`() {
        metrics.recordOutcome(ProcessingOutcome.APPLIED)
        metrics.recordOutcome(ProcessingOutcome.APPLIED)
        metrics.recordOutcome(ProcessingOutcome.STALE_DISCARDED)

        assertEquals(2.0, countFor(ProcessingOutcome.APPLIED))
        assertEquals(1.0, countFor(ProcessingOutcome.STALE_DISCARDED))
        assertEquals(0.0, countFor(ProcessingOutcome.DECLINED_SKIPPED))
    }

    /**
     * Every outcome is registered up front, not lazily on first occurrence. A counter that only
     * appears once it fires is a trap for alerting: `rate(...) == 0` and "series does not
     * exist" are different conditions, and a dashboard built on the missing series silently
     * shows nothing instead of zero.
     */
    @Test
    fun `registers a counter for every outcome before any event arrives`() {
        ProcessingOutcome.entries.forEach { outcome ->
            assertEquals(0.0, countFor(outcome), "expected a pre-registered counter for $outcome")
        }
    }

    @Test
    fun `counts rejected events`() {
        metrics.recordRejected()

        assertEquals(1.0, registry.get("balance.transactions.rejected").counter().count())
    }
}
