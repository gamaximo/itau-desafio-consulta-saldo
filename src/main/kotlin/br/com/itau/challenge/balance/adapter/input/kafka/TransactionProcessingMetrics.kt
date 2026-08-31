package br.com.itau.challenge.balance.adapter.input.kafka

import br.com.itau.challenge.balance.domain.model.ProcessingOutcome
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

private const val PROCESSED_METRIC = "balance.transactions.processed"
private const val REJECTED_METRIC = "balance.transactions.rejected"

/**
 * Business metrics for the ingestion pipeline.
 *
 * Counters are split by outcome rather than lumped into a single "processed" number because
 * the interesting signals are ratios, not totals. Throughput alone says nothing: a consumer
 * that discards every event as stale looks exactly as busy as a healthy one on a raw message
 * count, and only the `stale_discarded` share tells the two apart.
 */
@Component
class TransactionProcessingMetrics(
    registry: MeterRegistry,
) {
    private val processed: Map<ProcessingOutcome, Counter> =
        ProcessingOutcome.entries.associateWith { outcome ->
            Counter
                .builder(PROCESSED_METRIC)
                .description("Transaction events consumed, by what the pipeline did with them")
                .tag("outcome", outcome.name.lowercase())
                .register(registry)
        }

    private val rejected: Counter =
        Counter
            .builder(REJECTED_METRIC)
            .description("Transaction events rejected as unprocessable and sent to the dead letter topic")
            .register(registry)

    fun recordOutcome(outcome: ProcessingOutcome) = processed.getValue(outcome).increment()

    fun recordRejected() = rejected.increment()
}
