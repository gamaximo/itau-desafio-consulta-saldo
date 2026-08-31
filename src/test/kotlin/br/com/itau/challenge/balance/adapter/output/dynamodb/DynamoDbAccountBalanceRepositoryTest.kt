package br.com.itau.challenge.balance.adapter.output.dynamodb

import br.com.itau.challenge.balance.fixture.ACCOUNT_ID
import br.com.itau.challenge.balance.fixture.OWNER_ID
import br.com.itau.challenge.balance.fixture.TRANSACTION_ID
import br.com.itau.challenge.balance.fixture.TRANSACTION_TIMESTAMP
import br.com.itau.challenge.balance.fixture.accountBalance
import br.com.itau.challenge.balance.fixture.money
import br.com.itau.challenge.balance.port.output.AccountBalanceStorageException
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.BDDMockito.given
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TABLE = "AccountBalances"

class DynamoDbAccountBalanceRepositoryTest {

    private val client = mock(DynamoDbClient::class.java)
    private val repository = DynamoDbAccountBalanceRepository(client, TABLE)

    private fun capturedRequest(): PutItemRequest {
        val captor = ArgumentCaptor.forClass(PutItemRequest::class.java)
        verify(client).putItem(captor.capture())
        return captor.value
    }

    @Test
    fun `writes the balance to the configured table`() {
        given(client.putItem(any(PutItemRequest::class.java))).willReturn(PutItemResponse.builder().build())

        assertTrue(repository.saveIfNewer(accountBalance(balance = money(amount = "183.12"))))

        val request = capturedRequest()
        assertEquals(TABLE, request.tableName())
        assertEquals(ACCOUNT_ID, request.item()["accountId"]?.s())
        assertEquals(OWNER_ID, request.item()["owner"]?.s())
        assertEquals(TRANSACTION_ID, request.item()["lastTransactionId"]?.s())
        assertEquals("BRL", request.item()["balanceCurrency"]?.s())
    }

    /**
     * Stored as an exact decimal string in a Number attribute. Routed through a Double, a value
     * like 183.12 would be written as 183.11999999999999 and the stored balance would be wrong
     * by a cent — the kind of defect that only shows up in a reconciliation months later.
     */
    @Test
    fun `stores the amount without floating point drift`() {
        given(client.putItem(any(PutItemRequest::class.java))).willReturn(PutItemResponse.builder().build())

        repository.saveIfNewer(accountBalance(balance = money(amount = "183.12")))

        assertEquals("183.12", capturedRequest().item()["balanceAmount"]?.n())
    }

    /** Large values must not be written in scientific notation, which DynamoDB rejects. */
    @Test
    fun `stores large amounts in plain notation`() {
        given(client.putItem(any(PutItemRequest::class.java))).willReturn(PutItemResponse.builder().build())

        repository.saveIfNewer(accountBalance(balance = money(amount = "12000000000")))

        assertEquals("12000000000.00", capturedRequest().item()["balanceAmount"]?.n())
    }

    @Test
    fun `guards the write with a strictly-newer condition`() {
        given(client.putItem(any(PutItemRequest::class.java))).willReturn(PutItemResponse.builder().build())

        repository.saveIfNewer(accountBalance(version = TRANSACTION_TIMESTAMP))

        val request = capturedRequest()
        assertEquals("attribute_not_exists(#accountId) OR #version < :version", request.conditionExpression())
        assertEquals("version", request.expressionAttributeNames()["#version"])
        assertEquals(TRANSACTION_TIMESTAMP.toString(), request.expressionAttributeValues()[":version"]?.n())
    }

    /**
     * `version` and `owner` are DynamoDB reserved words. Referencing them directly in the
     * expression would fail to parse at runtime — and only at runtime, on the first write.
     */
    @Test
    fun `aliases reserved attribute names`() {
        given(client.putItem(any(PutItemRequest::class.java))).willReturn(PutItemResponse.builder().build())

        repository.saveIfNewer(accountBalance())

        val names = capturedRequest().expressionAttributeNames()
        assertEquals("accountId", names["#accountId"])
        assertEquals("version", names["#version"])
    }

    /**
     * The core case for both duplicates and out-of-order events: a rejected condition is a
     * normal `false`, never an exception. If this leaked as an error, every replayed message
     * would be retried and then dead-lettered.
     */
    @Test
    fun `reports not-applied when the condition rejects the write`() {
        given(client.putItem(any(PutItemRequest::class.java)))
            .willThrow(ConditionalCheckFailedException.builder().message("condition failed").build())

        assertFalse(repository.saveIfNewer(accountBalance()))
    }

    /**
     * An infrastructure failure, by contrast, must surface — it is retryable, and swallowing it
     * would commit the offset for an event that was never applied, losing the balance silently.
     */
    @Test
    fun `translates an SDK failure into a storage exception`() {
        given(client.putItem(any(PutItemRequest::class.java)))
            .willThrow(SdkClientException.builder().message("connection reset").build())

        val exception = assertFailsWith<AccountBalanceStorageException> { repository.saveIfNewer(accountBalance()) }

        assertTrue(exception.message!!.contains(ACCOUNT_ID))
    }

    /** Written for operators, never read back — but it still has to be there and be correct. */
    @Test
    fun `stores a human-readable copy of the update time`() {
        given(client.putItem(any(PutItemRequest::class.java))).willReturn(PutItemResponse.builder().build())

        repository.saveIfNewer(accountBalance(version = TRANSACTION_TIMESTAMP))

        assertEquals("2025-07-04T15:02:44.589998Z", capturedRequest().item()["updatedAt"]?.s())
    }
}
