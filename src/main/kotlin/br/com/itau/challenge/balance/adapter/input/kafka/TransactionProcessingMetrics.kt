package br.com.itau.challenge.balance.adapter.input.kafka

import br.com.itau.challenge.balance.domain.model.ProcessingOutcome
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

private const val PROCESSED_METRIC = "balance.transactions.processed"
private const val REJECTED_METRIC = "balance.transactions.rejected"

/**
 * Métricas de negócio do pipeline de ingestão.
 *
 * Os contadores são separados por resultado, em vez de somados num único número de
 * "processados", porque os sinais interessantes são proporções, não totais. Throughput sozinho
 * não diz nada: um consumidor que descarta todos os eventos como obsoletos parece tão ocupado
 * quanto um saudável se você olhar apenas a contagem bruta de mensagens, e só a fatia de
 * `stale_discarded` distingue os dois.
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
