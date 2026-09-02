package br.com.itau.challenge.balance.adapter.input.kafka

import org.apache.kafka.common.TopicPartitionInfo
import org.apache.kafka.clients.admin.TopicDescription
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.springframework.boot.health.contributor.Status
import org.springframework.kafka.core.KafkaAdmin
import kotlin.test.assertEquals

private const val TOPICO = "transacoes-financeiras-processadas"

class KafkaHealthIndicatorTest {

    private val kafkaAdmin = mock(KafkaAdmin::class.java)
    private val indicator = KafkaHealthIndicator(kafkaAdmin, TOPICO)

    @Test
    fun `reporta UP quando o broker responde`() {
        val descricao =
            TopicDescription(TOPICO, false, listOf(TopicPartitionInfo(0, null, emptyList(), emptyList())))
        given(kafkaAdmin.describeTopics(TOPICO)).willReturn(mapOf(TOPICO to descricao))

        val health = indicator.health()

        assertEquals(Status.UP, health.status)
        assertEquals(TOPICO, health.details["topic"])
        assertEquals(1, health.details["partitions"])
    }

    /**
     * O ponto cego que este indicador fecha: com o broker fora, a API segue respondendo e a
     * readiness segue UP, então nada indicaria que o serviço parou de receber transações. Os
     * saldos congelariam em silêncio.
     */
    @Test
    fun `reporta DOWN quando o broker esta inacessivel`() {
        given(kafkaAdmin.describeTopics(TOPICO)).willThrow(RuntimeException("connection refused"))

        assertEquals(Status.DOWN, indicator.health().status)
    }

    /** Um tópico apagado por engano produz o mesmo sintoma de um broker fora: ingestão parada. */
    @Test
    fun `reporta UP com zero particoes quando o topico nao e descrito`() {
        given(kafkaAdmin.describeTopics(TOPICO)).willReturn(emptyMap())

        assertEquals(0, indicator.health().details["partitions"])
    }
}
