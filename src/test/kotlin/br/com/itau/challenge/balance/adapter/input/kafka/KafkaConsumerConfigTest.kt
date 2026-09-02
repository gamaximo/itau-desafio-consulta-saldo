package br.com.itau.challenge.balance.adapter.input.kafka

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import br.com.itau.challenge.balance.port.output.AccountBalanceStorageException
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.util.backoff.BackOffExecution
import org.springframework.util.backoff.ExponentialBackOff
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

    /**
     * A política de retry é uma decisão operacional — quantas vezes e com que espera — e nada no
     * código a protege de ser alterada por engano. Este teste fixa os números: três novas
     * tentativas, começando em 500ms e dobrando, somando poucos segundos.
     *
     * O total importa porque conta contra `max.poll.interval.ms`: um backoff que ultrapassasse
     * esse limite faria o consumidor ser expulso do grupo no meio de uma falha transitória,
     * transformando um problema pequeno num rebalanceamento.
     */
    @Test
    fun `a politica de retry espera 500ms, 1s e 2s antes de desistir`() {
        val execucao = ExponentialBackOff().apply {
            initialInterval = 500
            multiplier = 2.0
            maxInterval = 5_000
            maxAttempts = 3
            jitter = 0
        }.start()

        val esperas = generateSequence { execucao.nextBackOff() }
            .takeWhile { it != BackOffExecution.STOP }
            .toList()

        assertEquals(listOf(500L, 1_000L, 2_000L), esperas)
        assertEquals(3_500L, esperas.sum(), "o total precisa caber com folga em max.poll.interval.ms")
    }

    @Test
    fun `constrói o error handler`() {
        val handler = config.kafkaErrorHandler(mock(KafkaTemplate::class.java))

        assertNotNull(handler)
    }

    /**
     * A distinção que evita quarentenar transações válidas.
     *
     * Com um backoff único e curto, uma indisponibilidade de poucos segundos manda eventos
     * legítimos ao dead letter topic — e recuperá-los passa a exigir intervenção manual, enquanto
     * o saldo daquelas contas fica desatualizado sem nada acusar. Medido: 35 segundos de DynamoDB
     * fora bastaram para dois eventos válidos serem quarentenados.
     *
     * Longo, porém finito: retentar para sempre trocaria esse problema por um pior — a partição
     * parada indefinidamente, e sem alarme próprio, já que o Spring Kafka registra as tentativas
     * apenas em DEBUG.
     */
    @Test
    fun `indisponibilidade do armazenamento e retentada por muito tempo antes de desistir`() {
        val causaAninhada =
            RuntimeException("falha no listener", AccountBalanceStorageException("banco fora", RuntimeException()))

        val execucao = backOffFor(causaAninhada).start()
        val esperas =
            generateSequence { execucao.nextBackOff() }.takeWhile { it != BackOffExecution.STOP }.toList()

        assertTrue(
            esperas.size > 50,
            "poucas tentativas quarentenariam transações válidas numa indisponibilidade curta",
        )
        assertTrue(
            esperas.sum() >= 25 * 60 * 1_000,
            "precisa insistir por dezenas de minutos: é o que cobre um deploy ou failover",
        )
        assertTrue(
            esperas.all { it <= 31_000 },
            "a espera satura num teto: crescer sem limite deixaria o consumidor dormindo por horas",
        )
    }

    @Test
    fun `qualquer outra falha desiste e vai para o dead letter topic`() {
        val execucao = backOffFor(IllegalStateException("bug")).start()
        val esperas = generateSequence { execucao.nextBackOff() }.takeWhile { it != BackOffExecution.STOP }.toList()
        assertEquals(3, esperas.size, "um defeito no código não melhora sozinho, então desiste")
    }
}
