package br.com.itau.challenge.balance.adapter.input.kafka

import br.com.itau.challenge.balance.port.output.AccountBalanceProvider
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the real listener against a real broker and a real DynamoDB: a message is published
 * and nothing is invoked directly — the assertion is that the running application consumed it
 * and projected the balance on its own.
 *
 * Requires live infrastructure — run with `make integration-test`.
 */
@SpringBootTest
class TransactionEventConsumerIntegrationTest(
    @Autowired private val kafkaTemplate: KafkaTemplate<String, String>,
    @Autowired private val accountBalanceProvider: AccountBalanceProvider,
    @Autowired private val consumerFactory: ConsumerFactory<String, String>,
    @Value("\${transactions.topic-name}") private val topic: String,
) {
    private fun event(
        accountId: String,
        amount: String,
        timestamp: Long,
        transactionId: String = UUID.randomUUID().toString(),
        type: String = "CREDIT",
    ) = """
        {
          "transaction": {
            "id": "$transactionId",
            "type": "$type",
            "amount": 10.00,
            "currency": "BRL",
            "status": "APPROVED",
            "timestamp": $timestamp
          },
          "account": {
            "id": "$accountId",
            "owner": "${UUID.randomUUID()}",
            "created_at": 1634874339000000,
            "status": "ENABLED",
            "balance": { "amount": $amount, "currency": "BRL" }
          }
        }
        """.trimIndent()

    /** Polls until [condition] holds or the deadline passes, so the test never sleeps blindly. */
    private fun <T> awaitValue(
        timeout: Duration = Duration.ofSeconds(30),
        supplier: () -> T?,
    ): T? {
        val deadline = System.currentTimeMillis() + timeout.toMillis()
        while (System.currentTimeMillis() < deadline) {
            supplier()?.let { return it }
            Thread.sleep(250)
        }
        return null
    }

    @Test
    fun `consumes a published event and projects the balance`() {
        val accountId = UUID.randomUUID().toString()

        kafkaTemplate.send(topic, event(accountId, amount = "183.12", timestamp = 1_000_000))

        val stored = awaitValue { accountBalanceProvider.findByAccountId(accountId) }

        assertEquals(BigDecimal("183.12"), assertNotNull(stored).balance.amount)
    }

    /**
     * The end-to-end version of the ordering guarantee: three events for one account, published
     * newest-first, with no Kafka key — so they spread across partitions and are consumed
     * concurrently. The balance must still settle on the newest one.
     */
    @Test
    fun `settles on the newest event even when older ones arrive later`() {
        val accountId = UUID.randomUUID().toString()

        kafkaTemplate.send(topic, event(accountId, amount = "300.00", timestamp = 3_000_000))
        kafkaTemplate.send(topic, event(accountId, amount = "100.00", timestamp = 1_000_000))
        kafkaTemplate.send(topic, event(accountId, amount = "200.00", timestamp = 2_000_000))

        val stored = awaitValue { accountBalanceProvider.findByAccountId(accountId) }
        assertNotNull(stored)

        // Give the two older events room to be consumed before asserting they changed nothing;
        // otherwise the assertion could pass simply because they had not arrived yet.
        Thread.sleep(2_000)

        val settled = assertNotNull(accountBalanceProvider.findByAccountId(accountId))
        assertEquals(BigDecimal("300.00"), settled.balance.amount)
        assertEquals(3_000_000, settled.version)
    }

    @Test
    fun `ignores a duplicated event`() {
        val accountId = UUID.randomUUID().toString()
        val transactionId = UUID.randomUUID().toString()
        val payload = event(accountId, amount = "250.00", timestamp = 5_000_000, transactionId = transactionId)

        repeat(3) { kafkaTemplate.send(topic, payload) }

        val stored = assertNotNull(awaitValue { accountBalanceProvider.findByAccountId(accountId) })
        assertEquals(BigDecimal("250.00"), stored.balance.amount)
        assertEquals(transactionId, stored.lastTransactionId)
    }

    /**
     * An unprocessable payload must reach the dead letter topic without ever touching the
     * balance, and without stalling the partition it arrived on.
     */
    @Test
    fun `dead-letters an unprocessable event and leaves the balance untouched`() {
        val accountId = UUID.randomUUID().toString()
        val marker = UUID.randomUUID().toString()
        val invalid = event(accountId, amount = "999.99", timestamp = 9_000_000, transactionId = marker, type = "TRANSFER")

        kafkaTemplate.send(topic, invalid)

        val deadLettered =
            awaitValue {
                consumeDeadLetters().firstOrNull { it.contains(marker) }
            }

        assertNotNull(deadLettered, "expected the invalid event to reach $topic.DLT")
        assertTrue(deadLettered.contains("TRANSFER"))
        assertNull(accountBalanceProvider.findByAccountId(accountId), "an invalid event must not project a balance")
    }

    private fun consumeDeadLetters(): List<String> {
        // A throwaway group id each time, so every call reads the topic from the beginning
        // instead of resuming a committed offset. `auto-offset-reset=earliest` comes from the
        // application's own consumer configuration.
        val consumer =
            consumerFactory.createConsumer("dlt-assertions-${UUID.randomUUID()}", null).apply {
                subscribe(listOf("$topic.DLT"))
            }

        return consumer.use { it.poll(Duration.ofSeconds(2)).map { record -> record.value() } }
    }
}
