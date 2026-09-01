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
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse
import software.amazon.awssdk.services.dynamodb.model.TableDescription
import software.amazon.awssdk.services.dynamodb.model.TableStatus
import kotlin.test.assertEquals

private const val TABELA = "AccountBalances"

class DynamoDbHealthIndicatorTest {

    private val client = mock(DynamoDbClient::class.java)
    private val indicator = DynamoDbHealthIndicator(client, TABELA)

    private fun tabelaAtiva() =
        DescribeTableResponse
            .builder()
            .table(TableDescription.builder().tableStatus(TableStatus.ACTIVE).build())
            .build()

    @Test
    fun `reporta UP quando a tabela responde`() {
        given(client.describeTable(any(DescribeTableRequest::class.java))).willReturn(tabelaAtiva())

        val health = indicator.health()

        assertEquals(Status.UP, health.status)
        assertEquals(TABELA, health.details["table"])
        assertEquals("ACTIVE", health.details["tableStatus"])
    }

    /**
     * O caso que dá sentido à probe de readiness: com o banco inacessível, a aplicação precisa
     * parar de se declarar pronta, para o orquestrador tirá-la do balanceamento em vez de mandar
     * tráfego para uma instância que só sabe devolver 503.
     */
    @Test
    fun `reporta DOWN quando o banco esta inacessivel`() {
        given(client.describeTable(any(DescribeTableRequest::class.java)))
            .willThrow(SdkClientException.builder().message("connection refused").build())

        val health = indicator.health()

        assertEquals(Status.DOWN, health.status)
        assertEquals(TABELA, health.details["table"])
    }

    /**
     * `DescribeTable` em vez de uma leitura de item: confere conectividade, credenciais e
     * existência da tabela sem consumir capacidade de leitura a cada probe — que roda de segundos
     * em segundos, para sempre.
     */
    @Test
    fun `consulta a tabela configurada sem consumir capacidade de leitura`() {
        given(client.describeTable(any(DescribeTableRequest::class.java))).willReturn(tabelaAtiva())

        indicator.health()

        val captor = ArgumentCaptor.forClass(DescribeTableRequest::class.java)
        verify(client).describeTable(captor.capture())
        assertEquals(TABELA, captor.value.tableName())
    }
}
