package br.com.itau.challenge.balance.adapter.input.kafka

import org.apache.kafka.clients.admin.TopicDescription
import org.apache.kafka.common.TopicPartitionInfo
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.springframework.boot.health.contributor.Status
import org.springframework.kafka.core.KafkaAdmin
import kotlin.test.assertEquals

private const val TOPICO = "transacoes-financeiras-processadas"
private const val DLT = "$TOPICO.DLT"

/**
 * O comportamento real do `describeTopics` guia estes testes: quando um tópico não existe, ele
 * **lança exceção** em vez de devolver um mapa parcial. Uma primeira versão deste teste simulou o
 * mapa parcial e passava, enquanto em runtime o detalhe do health saía como uma exceção genérica
 * que não dizia qual tópico faltava.
 */
class KafkaHealthIndicatorTest {

    private val kafkaAdmin = mock(KafkaAdmin::class.java)
    private val indicator = KafkaHealthIndicator(kafkaAdmin, TOPICO)

    private fun descricaoDe(nome: String) =
        mapOf(nome to TopicDescription(nome, false, listOf(TopicPartitionInfo(0, null, emptyList(), emptyList()))))

    private fun ausente(nome: String) {
        given(kafkaAdmin.describeTopics(nome)).willThrow(RuntimeException("Failed to obtain topic descriptions"))
    }

    @Test
    fun `reporta UP quando o broker responde e os dois topicos existem`() {
        given(kafkaAdmin.describeTopics(TOPICO)).willReturn(descricaoDe(TOPICO))
        given(kafkaAdmin.describeTopics(DLT)).willReturn(descricaoDe(DLT))

        val health = indicator.health()

        assertEquals(Status.UP, health.status)
        assertEquals(TOPICO, health.details["topic"])
        assertEquals(DLT, health.details["deadLetterTopic"])
    }

    /**
     * O ponto cego que este indicador fecha: com o broker fora, a API segue respondendo e a
     * readiness segue UP, então nada indicaria que o serviço parou de receber transações.
     */
    @Test
    fun `reporta DOWN quando nenhum topico responde`() {
        ausente(TOPICO)
        ausente(DLT)

        val health = indicator.health()

        assertEquals(Status.DOWN, health.status)
        assertEquals(listOf(TOPICO, DLT), health.details["topicosIndisponiveis"])
        // A mensagem distingue este caso do tópico apagado: as ações são diferentes.
        assertEquals(true, (health.details["motivo"] as String).contains("broker"))
    }

    /**
     * A falta do dead letter topic é especialmente traiçoeira: o consumo segue normal até aparecer
     * um payload inválido, e aí o recoverer não tem para onde publicar e a partição trava. Medido:
     * apagando o DLT, um evento inválido ficou preso com lag 1, e o tópico não volta sozinho —
     * o KafkaAdmin só cria na inicialização.
     */
    @Test
    fun `aponta exatamente qual topico foi apagado`() {
        given(kafkaAdmin.describeTopics(TOPICO)).willReturn(descricaoDe(TOPICO))
        ausente(DLT)

        val health = indicator.health()

        assertEquals(Status.DOWN, health.status)
        assertEquals(listOf(DLT), health.details["topicosIndisponiveis"])
        assertEquals(true, (health.details["motivo"] as String).contains("ausente"))
    }
}
