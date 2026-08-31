package br.com.itau.challenge.balance.adapter.input.kafka

import br.com.itau.challenge.balance.adapter.input.kafka.dto.TransactionEventMessage
import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import br.com.itau.challenge.balance.domain.model.ProcessedTransaction
import br.com.itau.challenge.balance.domain.model.ProcessingOutcome
import br.com.itau.challenge.balance.port.input.ProcessTransactionUseCase
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

@Component
class TransactionEventConsumer(
    private val processTransactionUseCase: ProcessTransactionUseCase,
    private val objectMapper: ObjectMapper,
    private val metrics: TransactionProcessingMetrics,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${transactions.topic-name}"])
    fun consume(payload: String) {
        val event = parse(payload)
        val outcome = processTransactionUseCase.process(event)
        metrics.recordOutcome(outcome)

        // Applied balances are the hot path and would drown the logs at production volume, so
        // they log at DEBUG. The two non-applied outcomes stay at INFO: they are rare enough
        // to be affordable and are exactly what an operator needs when a client swears their
        // balance is wrong.
        when (outcome) {
            ProcessingOutcome.APPLIED ->
                logger.debug(
                    "Balance applied: account={} transaction={} version={}",
                    event.account.id,
                    event.transaction.id,
                    event.transaction.timestamp,
                )

            ProcessingOutcome.STALE_DISCARDED ->
                logger.info(
                    "Stale event discarded, stored balance is already newer: account={} transaction={} version={}",
                    event.account.id,
                    event.transaction.id,
                    event.transaction.timestamp,
                )

            ProcessingOutcome.DECLINED_SKIPPED ->
                logger.info(
                    "Declined transaction skipped by configuration: account={} transaction={}",
                    event.account.id,
                    event.transaction.id,
                )
        }
    }

    private fun parse(payload: String): ProcessedTransaction =
        try {
            objectMapper.readValue(payload, TransactionEventMessage::class.java).toDomain()
        } catch (exception: JacksonException) {
            // Syntactically broken JSON. Rethrown as a domain exception so that the error
            // handler sees a single non-retryable type and dead-letters it, instead of
            // retrying a payload that cannot possibly parse on the second attempt.
            metrics.recordRejected()
            throw InvalidTransactionEventException("Malformed transaction event payload: ${exception.message}")
        } catch (exception: InvalidTransactionEventException) {
            // Structurally valid JSON that breaks the contract — missing field, unknown enum,
            // malformed UUID. Counted here so the metric covers both rejection paths.
            metrics.recordRejected()
            throw exception
        }
}
