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
    fun `declares the transactions topic with the configured partition count`() {
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
    fun `derives the dead letter topic from the transactions topic`() {
        val topic = config.transactionsDeadLetterTopic(TOPIC, partitions = 3)

        assertEquals("$TOPIC.DLT", topic.name())
        assertEquals(3, topic.numPartitions())
    }

    @Test
    fun `routes a rejected record to the dead letter topic of its own source topic`() {
        val record = ConsumerRecord("some-topic", 2, 42L, "key", "value")

        val destination = deadLetterDestinationFor(record, IllegalStateException("boom"))

        assertEquals("some-topic.DLT", destination.topic())
    }

    /**
     * A partição -1 deixa o particionador do produtor escolher. Fixar a partição de origem
     * falharia de imediato sempre que o DLT tivesse menos partições que o tópico que ele espelha.
     */
    @Test
    fun `does not pin the dead letter record to the source partition`() {
        val record = ConsumerRecord("some-topic", 7, 1L, "key", "value")

        assertEquals(-1, deadLetterDestinationFor(record, RuntimeException("boom")).partition())
    }

    @Test
    fun `builds an error handler`() {
        val handler = config.kafkaErrorHandler(mock(KafkaTemplate::class.java))

        assertNotNull(handler)
    }
}
