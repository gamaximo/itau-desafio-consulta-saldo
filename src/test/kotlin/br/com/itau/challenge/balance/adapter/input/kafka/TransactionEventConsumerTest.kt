package br.com.itau.challenge.balance.adapter.input.kafka

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import br.com.itau.challenge.balance.domain.model.ProcessedTransaction
import br.com.itau.challenge.balance.domain.model.ProcessingOutcome
import br.com.itau.challenge.balance.fixture.ACCOUNT_ID
import br.com.itau.challenge.balance.port.input.ProcessTransactionUseCase
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val VALID_PAYLOAD = """
{
  "transaction": {
    "id": "8e8ae808-b154-48b5-9f3e-553935cc4543",
    "type": "CREDIT",
    "amount": 97.07,
    "currency": "BRL",
    "status": "APPROVED",
    "timestamp": 1751641364589998
  },
  "account": {
    "id": "5b19c8b6-0cc4-4c72-a989-0c2ee15fa975",
    "owner": "315e3cfe-f4af-4cd2-b298-a449e614349a",
    "created_at": 1634874339000000,
    "status": "ENABLED",
    "balance": { "amount": 183.12, "currency": "BRL" }
  }
}
"""

class TransactionEventConsumerTest {

    private val registry = SimpleMeterRegistry()
    private val metrics = TransactionProcessingMetrics(registry)

    private var received: ProcessedTransaction? = null

    private fun consumerReturning(outcome: ProcessingOutcome) =
        TransactionEventConsumer(
            processTransactionUseCase =
                ProcessTransactionUseCase { event ->
                    received = event
                    outcome
                },
            objectMapper = jacksonObjectMapper(),
            metrics = metrics,
        )

    private fun rejectedCount() = registry.get("balance.transactions.rejected").counter().count()

    private fun outcomeCount(outcome: ProcessingOutcome) =
        registry.get("balance.transactions.processed").tag("outcome", outcome.name.lowercase()).counter().count()

    @Test
    fun `passes a valid event to the use case`() {
        consumerReturning(ProcessingOutcome.APPLIED).consume(VALID_PAYLOAD)

        val event = assertNotNull(received)
        assertEquals(ACCOUNT_ID, event.account.id)
        assertEquals(1.0, outcomeCount(ProcessingOutcome.APPLIED))
    }

    /**
     * Each outcome takes a different logging branch, so all three are exercised — a broken
     * format string in the rarely-hit branch would only ever surface in production, at exactly
     * the moment someone is relying on that log line to explain a wrong balance.
     */
    @Test
    fun `records every outcome the use case can report`() {
        ProcessingOutcome.entries.forEach { outcome ->
            consumerReturning(outcome).consume(VALID_PAYLOAD)
            assertEquals(1.0, outcomeCount(outcome), "expected $outcome to be counted once")
        }
    }

    @Test
    fun `rejects syntactically broken JSON without calling the use case`() {
        val consumer = consumerReturning(ProcessingOutcome.APPLIED)

        assertFailsWith<InvalidTransactionEventException> { consumer.consume("{ not json") }

        assertNull(received)
        assertEquals(1.0, rejectedCount())
    }

    @Test
    fun `rejects a payload that breaks the contract`() {
        val consumer = consumerReturning(ProcessingOutcome.APPLIED)
        val missingAccount = """{"transaction": {"id": "8e8ae808-b154-48b5-9f3e-553935cc4543"}}"""

        assertFailsWith<InvalidTransactionEventException> { consumer.consume(missingAccount) }

        assertNull(received)
        assertEquals(1.0, rejectedCount())
    }

    /**
     * The exception must escape the listener. Swallowing it here would commit the offset and
     * silently drop the message — the error handler never runs, and the event reaches neither
     * the balance nor the dead letter topic.
     */
    @Test
    fun `lets the rejection propagate so the error handler can dead-letter it`() {
        val consumer = consumerReturning(ProcessingOutcome.APPLIED)
        val unknownEnum = VALID_PAYLOAD.replace("\"CREDIT\"", "\"TRANSFER\"")

        assertFailsWith<InvalidTransactionEventException> { consumer.consume(unknownEnum) }
    }
}
