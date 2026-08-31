package br.com.itau.challenge.balance.adapter.input.kafka

import br.com.itau.challenge.balance.adapter.input.kafka.dto.TransactionEventMessage
import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import br.com.itau.challenge.balance.domain.model.AccountStatus
import br.com.itau.challenge.balance.domain.model.TransactionStatus
import br.com.itau.challenge.balance.domain.model.TransactionType
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The payload published on `transacoes-financeiras-processadas`, verbatim from the spec. */
private const val SAMPLE_PAYLOAD = """
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
    "balance": {
      "amount": 183.12,
      "currency": "BRL"
    }
  }
}
"""

class TransactionEventMapperTest {

    private val objectMapper = jacksonObjectMapper()

    private fun parse(payload: String) = objectMapper.readValue(payload, TransactionEventMessage::class.java)

    @Test
    fun `maps the specification payload into the domain`() {
        val event = parse(SAMPLE_PAYLOAD).toDomain()

        assertEquals("8e8ae808-b154-48b5-9f3e-553935cc4543", event.transaction.id)
        assertEquals(TransactionType.CREDIT, event.transaction.type)
        assertEquals(TransactionStatus.APPROVED, event.transaction.status)
        assertEquals(1751641364589998L, event.transaction.timestamp)
        assertEquals("5b19c8b6-0cc4-4c72-a989-0c2ee15fa975", event.account.id)
        assertEquals(AccountStatus.ENABLED, event.account.status)
        assertEquals(1634874339000000L, event.account.createdAt)
    }

    /**
     * The snake_case field is the one most likely to break silently: bound to the wrong name it
     * would arrive as null and the event would be dead-lettered as "missing field", with the
     * real cause being a mapping typo.
     */
    @Test
    fun `binds the snake_case created_at field`() {
        assertEquals(1634874339000000L, parse(SAMPLE_PAYLOAD).account?.createdAt)
    }

    /**
     * The decisive precision test. Bound through a Double, 97.07 becomes 97.06999999999999
     * and the exact decimal is gone before any business code runs.
     */
    @Test
    fun `keeps monetary amounts exact`() {
        val event = parse(SAMPLE_PAYLOAD).toDomain()

        assertEquals(BigDecimal("97.07"), event.transaction.amount.amount)
        assertEquals(BigDecimal("183.12"), event.account.balance.amount)
    }

    @Test
    fun `ignores properties the producer added`() {
        val withExtras = SAMPLE_PAYLOAD.replace("\"type\": \"CREDIT\"", "\"type\": \"CREDIT\", \"channel\": \"PIX\"")

        assertEquals(TransactionType.CREDIT, parse(withExtras).toDomain().transaction.type)
    }

    @Test
    fun `reports which required field is missing`() {
        val missingAccount = """{"transaction": {"id": "8e8ae808-b154-48b5-9f3e-553935cc4543"}}"""

        val exception = assertFailsWith<InvalidTransactionEventException> { parse(missingAccount).toDomain() }

        assertTrue(exception.message!!.contains("account"))
    }

    @Test
    fun `reports a missing nested balance`() {
        val withoutBalance = SAMPLE_PAYLOAD.replace(
            """"balance": {
      "amount": 183.12,
      "currency": "BRL"
    }""",
            """"unused": true""",
        )

        val exception = assertFailsWith<InvalidTransactionEventException> { parse(withoutBalance).toDomain() }

        assertTrue(exception.message!!.contains("account.balance"))
    }

    @Test
    fun `rejects an empty object`() {
        assertFailsWith<InvalidTransactionEventException> { parse("{}").toDomain() }
    }

    @Test
    fun `rejects an unknown enum value`() {
        val unknownType = SAMPLE_PAYLOAD.replace("\"CREDIT\"", "\"TRANSFER\"")

        assertFailsWith<InvalidTransactionEventException> { parse(unknownType).toDomain() }
    }

    @Test
    fun `rejects a malformed identifier`() {
        val badId = SAMPLE_PAYLOAD.replace("5b19c8b6-0cc4-4c72-a989-0c2ee15fa975", "not-a-uuid")

        assertFailsWith<InvalidTransactionEventException> { parse(badId).toDomain() }
    }
}
