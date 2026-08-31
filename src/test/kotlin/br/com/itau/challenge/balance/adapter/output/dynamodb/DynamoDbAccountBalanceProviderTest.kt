package br.com.itau.challenge.balance.adapter.output.dynamodb

import br.com.itau.challenge.balance.fixture.ACCOUNT_ID
import br.com.itau.challenge.balance.fixture.OWNER_ID
import br.com.itau.challenge.balance.fixture.TRANSACTION_ID
import br.com.itau.challenge.balance.fixture.TRANSACTION_TIMESTAMP
import br.com.itau.challenge.balance.port.output.AccountBalanceStorageException
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.BDDMockito.given
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TABLE = "AccountBalances"

private fun storedItem() =
    mapOf(
        "accountId" to AttributeValue.builder().s(ACCOUNT_ID).build(),
        "owner" to AttributeValue.builder().s(OWNER_ID).build(),
        "balanceAmount" to AttributeValue.builder().n("183.12").build(),
        "balanceCurrency" to AttributeValue.builder().s("BRL").build(),
        "lastTransactionId" to AttributeValue.builder().s(TRANSACTION_ID).build(),
        "version" to AttributeValue.builder().n(TRANSACTION_TIMESTAMP.toString()).build(),
        "updatedAt" to AttributeValue.builder().s("2025-07-04T15:02:44.589998Z").build(),
    )

class DynamoDbAccountBalanceProviderTest {

    private val client = mock(DynamoDbClient::class.java)
    private val provider = DynamoDbAccountBalanceProvider(client, TABLE)

    @Test
    fun `reads a stored balance back into the domain model`() {
        given(client.getItem(any(GetItemRequest::class.java)))
            .willReturn(GetItemResponse.builder().item(storedItem()).build())

        val balance = assertNotNull(provider.findByAccountId(ACCOUNT_ID))

        assertEquals(ACCOUNT_ID, balance.accountId)
        assertEquals(OWNER_ID, balance.owner)
        assertEquals(BigDecimal("183.12"), balance.balance.amount)
        assertEquals("BRL", balance.balance.currency)
        assertEquals(TRANSACTION_ID, balance.lastTransactionId)
        assertEquals(TRANSACTION_TIMESTAMP, balance.version)
    }

    /**
     * `updatedAt` is recomputed from the stored version rather than read from the item, so the
     * write-only copy can never contradict what the API reports.
     */
    @Test
    fun `derives updatedAt from the stored version, not from the stored string`() {
        val itemWithWrongDate =
            storedItem() + ("updatedAt" to AttributeValue.builder().s("1999-01-01T00:00:00Z").build())
        given(client.getItem(any(GetItemRequest::class.java)))
            .willReturn(GetItemResponse.builder().item(itemWithWrongDate).build())

        val balance = assertNotNull(provider.findByAccountId(ACCOUNT_ID))

        assertEquals(Instant.parse("2025-07-04T15:02:44.589998Z"), balance.updatedAt)
    }

    @Test
    fun `queries the configured table by partition key`() {
        given(client.getItem(any(GetItemRequest::class.java)))
            .willReturn(GetItemResponse.builder().item(storedItem()).build())

        provider.findByAccountId(ACCOUNT_ID)

        val captor = ArgumentCaptor.forClass(GetItemRequest::class.java)
        verify(client).getItem(captor.capture())
        assertEquals(TABLE, captor.value.tableName())
        assertEquals(ACCOUNT_ID, captor.value.key()["accountId"]?.s())
    }

    /**
     * Without a strongly consistent read, a client that just saw its transaction settle could
     * query and be served the previous balance from a lagging replica.
     */
    @Test
    fun `reads consistently`() {
        given(client.getItem(any(GetItemRequest::class.java)))
            .willReturn(GetItemResponse.builder().item(storedItem()).build())

        provider.findByAccountId(ACCOUNT_ID)

        val captor = ArgumentCaptor.forClass(GetItemRequest::class.java)
        verify(client).getItem(captor.capture())
        assertEquals(true, captor.value.consistentRead())
    }

    /**
     * DynamoDB answers a missing key with an empty item map rather than an absent one, so
     * `hasItem()` is the only correct check — testing the map for emptiness would work, but
     * relying on it would break the moment a projection legitimately returns no attributes.
     */
    @Test
    fun `returns null when the account has no stored balance`() {
        given(client.getItem(any(GetItemRequest::class.java))).willReturn(GetItemResponse.builder().build())

        assertNull(provider.findByAccountId(ACCOUNT_ID))
    }

    @Test
    fun `translates an SDK failure into a storage exception`() {
        given(client.getItem(any(GetItemRequest::class.java)))
            .willThrow(SdkClientException.builder().message("timeout").build())

        val exception = assertFailsWith<AccountBalanceStorageException> { provider.findByAccountId(ACCOUNT_ID) }

        assertTrue(exception.message!!.contains(ACCOUNT_ID))
    }
}
