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
     * Armazenado como string decimal exata num atributo Number. Passando por um Double, um valor
     * como 183.12 seria gravado como 183.11999999999999 e o saldo armazenado estaria errado por
     * um centavo — o tipo de defeito que só aparece numa conciliação meses depois.
     */
    @Test
    fun `stores the amount without floating point drift`() {
        given(client.putItem(any(PutItemRequest::class.java))).willReturn(PutItemResponse.builder().build())

        repository.saveIfNewer(accountBalance(balance = money(amount = "183.12")))

        assertEquals("183.12", capturedRequest().item()["balanceAmount"]?.n())
    }

    /** Valores grandes não podem ser gravados em notação científica, que o DynamoDB rejeita. */
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
     * `version` e `owner` são palavras reservadas do DynamoDB. Referenciá-las diretamente na
     * expressão falharia na interpretação em tempo de execução — e só em tempo de execução, na
     * primeira escrita.
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
     * O caso central tanto para duplicatas quanto para eventos fora de ordem: uma condição
     * rejeitada é um `false` normal, nunca uma exceção. Se isso vazasse como erro, toda mensagem
     * reenviada seria retentada e depois mandada para o DLT.
     */
    @Test
    fun `reports not-applied when the condition rejects the write`() {
        given(client.putItem(any(PutItemRequest::class.java)))
            .willThrow(ConditionalCheckFailedException.builder().message("condition failed").build())

        assertFalse(repository.saveIfNewer(accountBalance()))
    }

    /**
     * Uma falha de infraestrutura, em contrapartida, precisa aparecer — ela é retentável, e
     * engoli-la faria o offset ser commitado para um evento que nunca foi aplicado, perdendo o
     * saldo em silêncio.
     */
    @Test
    fun `translates an SDK failure into a storage exception`() {
        given(client.putItem(any(PutItemRequest::class.java)))
            .willThrow(SdkClientException.builder().message("connection reset").build())

        val exception = assertFailsWith<AccountBalanceStorageException> { repository.saveIfNewer(accountBalance()) }

        assertTrue(exception.message!!.contains(ACCOUNT_ID))
    }

    /** Escrito para operadores, nunca lido de volta — mas ainda assim precisa existir e estar correto. */
    @Test
    fun `stores a human-readable copy of the update time`() {
        given(client.putItem(any(PutItemRequest::class.java))).willReturn(PutItemResponse.builder().build())

        repository.saveIfNewer(accountBalance(version = TRANSACTION_TIMESTAMP))

        assertEquals("2025-07-04T15:02:44.589998Z", capturedRequest().item()["updatedAt"]?.s())
    }
}
