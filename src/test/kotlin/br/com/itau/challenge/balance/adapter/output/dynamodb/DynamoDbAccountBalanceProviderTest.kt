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
    fun `lê um saldo armazenado de volta para o modelo de domínio`() {
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
     * `updatedAt` é recalculado a partir da versão armazenada, em vez de lido do item, para que a
     * cópia somente-escrita nunca contradiga o que a API reporta.
     */
    @Test
    fun `deriva updatedAt da versão armazenada, e não da string armazenada`() {
        val itemWithWrongDate =
            storedItem() + ("updatedAt" to AttributeValue.builder().s("1999-01-01T00:00:00Z").build())
        given(client.getItem(any(GetItemRequest::class.java)))
            .willReturn(GetItemResponse.builder().item(itemWithWrongDate).build())

        val balance = assertNotNull(provider.findByAccountId(ACCOUNT_ID))

        assertEquals(Instant.parse("2025-07-04T15:02:44.589998Z"), balance.updatedAt)
    }

    @Test
    fun `consulta a tabela configurada pela partition key`() {
        given(client.getItem(any(GetItemRequest::class.java)))
            .willReturn(GetItemResponse.builder().item(storedItem()).build())

        provider.findByAccountId(ACCOUNT_ID)

        val captor = ArgumentCaptor.forClass(GetItemRequest::class.java)
        verify(client).getItem(captor.capture())
        assertEquals(TABLE, captor.value.tableName())
        assertEquals(ACCOUNT_ID, captor.value.key()["accountId"]?.s())
    }

    /**
     * Sem uma leitura fortemente consistente, um cliente que acabou de ver sua transação ser
     * liquidada poderia consultar e receber o saldo anterior, servido por uma réplica atrasada.
     */
    @Test
    fun `faz leitura fortemente consistente`() {
        given(client.getItem(any(GetItemRequest::class.java)))
            .willReturn(GetItemResponse.builder().item(storedItem()).build())

        provider.findByAccountId(ACCOUNT_ID)

        val captor = ArgumentCaptor.forClass(GetItemRequest::class.java)
        verify(client).getItem(captor.capture())
        assertEquals(true, captor.value.consistentRead())
    }

    /**
     * O DynamoDB responde a uma chave inexistente com um mapa de item vazio, e não ausente, então
     * `hasItem()` é a única checagem correta — testar se o mapa está vazio funcionaria, mas
     * depender disso quebraria no momento em que uma projeção legitimamente não retornasse
     * atributo nenhum.
     */
    @Test
    fun `retorna null quando a conta não tem saldo armazenado`() {
        given(client.getItem(any(GetItemRequest::class.java))).willReturn(GetItemResponse.builder().build())

        assertNull(provider.findByAccountId(ACCOUNT_ID))
    }

    @Test
    fun `traduz uma falha do SDK em exceção de armazenamento`() {
        given(client.getItem(any(GetItemRequest::class.java)))
            .willThrow(SdkClientException.builder().message("timeout").build())

        val exception = assertFailsWith<AccountBalanceStorageException> { provider.findByAccountId(ACCOUNT_ID) }

        assertTrue(exception.message!!.contains(ACCOUNT_ID))
    }
}
