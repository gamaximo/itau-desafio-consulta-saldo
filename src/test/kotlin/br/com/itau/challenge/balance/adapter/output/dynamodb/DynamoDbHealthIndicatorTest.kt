package br.com.itau.challenge.balance.adapter.output.dynamodb

import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.BDDMockito.given
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.boot.health.contributor.Status
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException
import kotlin.test.assertEquals

private const val TABELA = "AccountBalances"

class DynamoDbHealthIndicatorTest {

    private val client = mock(DynamoDbClient::class.java)
    private val indicator = DynamoDbHealthIndicator(client, TABELA)

    private fun respostaVazia() = GetItemResponse.builder().build()

    private fun requisicaoCapturada(): GetItemRequest {
        val captor = ArgumentCaptor.forClass(GetItemRequest::class.java)
        verify(client).getItem(captor.capture())
        return captor.value
    }

    @Test
    fun `reporta UP quando a tabela responde`() {
        given(client.getItem(any(GetItemRequest::class.java))).willReturn(respostaVazia())

        val health = indicator.health()

        assertEquals(Status.UP, health.status)
        assertEquals(TABELA, health.details["table"])
    }

    /**
     * O caso que dá sentido à probe de readiness: com o banco inacessível, a aplicação precisa
     * parar de se declarar pronta, para o orquestrador tirá-la do balanceamento em vez de mandar
     * tráfego para uma instância que só sabe devolver 503.
     */
    @Test
    fun `reporta DOWN quando o banco esta inacessivel`() {
        given(client.getItem(any(GetItemRequest::class.java)))
            .willThrow(SdkClientException.builder().message("connection refused").build())

        assertEquals(Status.DOWN, indicator.health().status)
    }

    /** Tabela ausente é um cenário real de deploy em ambiente novo, sem migração aplicada. */
    @Test
    fun `reporta DOWN quando a tabela nao existe`() {
        given(client.getItem(any(GetItemRequest::class.java)))
            .willThrow(ResourceNotFoundException.builder().message("requested resource not found").build())

        assertEquals(Status.DOWN, indicator.health().status)
    }

    /**
     * Usa `GetItem`, não `DescribeTable`.
     *
     * `DescribeTable` é operação de *control plane*: o limite de taxa é muito menor e
     * compartilhado por conta e região, não por tabela. Com a probe batendo a cada poucos
     * segundos, multiplicada por réplicas e por outras aplicações da mesma conta, ela vira fonte
     * de throttling — e aí a verificação derrubaria a readiness de instâncias saudáveis.
     */
    @Test
    fun `verifica pelo caminho de dados, nao pelo control plane`() {
        given(client.getItem(any(GetItemRequest::class.java))).willReturn(respostaVazia())

        indicator.health()

        assertEquals(TABELA, requisicaoCapturada().tableName())
    }

    /**
     * A chave da sonda é fixa e sabidamente inexistente: a resposta vazia é o resultado esperado,
     * e o que se verifica é que a chamada completou. Assim a probe não depende de nenhum dado
     * real da tabela nem consome capacidade relevante.
     */
    @Test
    fun `consulta uma chave reservada, sem depender de dados reais`() {
        given(client.getItem(any(GetItemRequest::class.java))).willReturn(respostaVazia())

        indicator.health()

        assertEquals("health-probe", requisicaoCapturada().key()["accountId"]?.s())
    }
}
