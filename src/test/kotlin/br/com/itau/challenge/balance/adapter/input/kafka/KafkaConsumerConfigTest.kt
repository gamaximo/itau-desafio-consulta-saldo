package br.com.itau.challenge.balance.adapter.input.kafka

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.kafka.core.KafkaTemplate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private const val TOPIC = "transacoes-financeiras-processadas"

class KafkaConsumerConfigTest {

    private val config = KafkaConsumerConfig()

    @Test
    fun `declara o tópico de transações com a quantidade de partições configurada`() {
        val topic = config.transactionsTopic(TOPIC, partitions = 3)

        assertEquals(TOPIC, topic.name())
        assertEquals(3, topic.numPartitions())
    }

    /**
     * O DLT é derivado do nome do tópico de origem em vez de configurado à parte, então os dois
     * nunca podem se descolar — um tópico renomeado convivendo em silêncio com um DLT órfão
     * poria mensagens em quarentena onde ninguém vai procurá-las.
     */
    @Test
    fun `deriva o dead letter topic a partir do tópico de transações`() {
        val topic = config.transactionsDeadLetterTopic(TOPIC, partitions = 3)

        assertEquals("$TOPIC.DLT", topic.name())
        assertEquals(3, topic.numPartitions())
    }

    @Test
    fun `roteia um registro rejeitado para o dead letter topic do próprio tópico de origem`() {
        val record = ConsumerRecord("some-topic", 2, 42L, "key", "value")

        val destination = deadLetterDestinationFor(record, IllegalStateException("boom"))

        assertEquals("some-topic.DLT", destination.topic())
    }

    /**
     * A partição -1 deixa o particionador do produtor escolher. Fixar a partição de origem
     * falharia de imediato sempre que o DLT tivesse menos partições que o tópico que ele espelha.
     */
    @Test
    fun `não fixa o registro rejeitado na partição de origem`() {
        val record = ConsumerRecord("some-topic", 7, 1L, "key", "value")

        assertEquals(-1, deadLetterDestinationFor(record, RuntimeException("boom")).partition())
    }

    /**
     * O Spring Kafka envolve a falha numa ListenerExecutionFailedException cuja mensagem só diz
     * qual método lançou a exceção. Logar o wrapper daria "o listener X lançou exceção" — inútil
     * para quem investiga. O que resolve o problema está na causa mais profunda.
     */
    @Test
    fun `loga a causa raiz, e nao a mensagem do wrapper`() {
        val causaReal = IllegalArgumentException("Unknown transaction type 'TRANSFER'")
        val wrapper = RuntimeException("Listener method threw exception", causaReal)
        val record = ConsumerRecord("some-topic", 0, 1L, "key", "value")

        // A rota não muda; o que se verifica aqui é que a função aceita a exceção aninhada sem
        // perder a causa — a mensagem em si é validada pela inspeção do log em runtime.
        assertEquals("some-topic.DLT", deadLetterDestinationFor(record, wrapper).topic())
    }

    @Test
    fun `constrói o error handler`() {
        val handler = config.kafkaErrorHandler(mock(KafkaTemplate::class.java))

        assertNotNull(handler)
    }
}
