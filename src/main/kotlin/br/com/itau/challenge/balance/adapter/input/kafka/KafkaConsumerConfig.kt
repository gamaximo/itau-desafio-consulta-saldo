package br.com.itau.challenge.balance.adapter.input.kafka

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.util.backoff.ExponentialBackOff

private const val DEAD_LETTER_SUFFIX = ".DLT"

/**
 * Qualquer partição do dead letter topic serve — a partição de origem perde o significado quando
 * a mensagem é posta em quarentena, e fixá-la faria o DLT falhar sempre que ele tivesse menos
 * partições que o tópico de origem.
 */
private const val ANY_PARTITION = -1

private val deadLetterLogger = LoggerFactory.getLogger("br.com.itau.challenge.balance.DeadLetter")

/**
 * Para onde vai um registro rejeitado, e o único lugar onde ele é logado.
 *
 * É uma função nomeada, e não uma lambda dentro da definição do bean, para que a regra de
 * roteamento e sua linha de log possam ser testadas diretamente, em vez de apenas através de um
 * container Kafka rodando.
 */
internal fun deadLetterDestinationFor(
    record: ConsumerRecord<*, *>,
    exception: Exception,
): TopicPartition {
    deadLetterLogger.error(
        "Dead-lettering unprocessable event: topic={} partition={} offset={} reason={}",
        record.topic(),
        record.partition(),
        record.offset(),
        exception.message,
    )
    return TopicPartition(record.topic() + DEAD_LETTER_SUFFIX, ANY_PARTITION)
}

@Configuration
class KafkaConsumerConfig {

    /**
     * Os tópicos são declarados como beans para que o `KafkaAdmin` os crie na inicialização pela
     * admin API. O Redpanda desta stack está com `auto_create_topics_enabled` desligado, então,
     * sem isso, a aplicação subiria, se inscreveria em nada e ficaria ali com aparência saudável
     * consumindo zero mensagens. A criação é idempotente: um tópico existente é deixado intacto,
     * contagem de partições incluída.
     */
    @Bean
    fun transactionsTopic(
        @Value("\${transactions.topic-name}") topicName: String,
        @Value("\${transactions.partitions}") partitions: Int,
    ): NewTopic = TopicBuilder.name(topicName).partitions(partitions).replicas(1).build()

    @Bean
    fun transactionsDeadLetterTopic(
        @Value("\${transactions.topic-name}") topicName: String,
        @Value("\${transactions.partitions}") partitions: Int,
    ): NewTopic = TopicBuilder.name(topicName + DEAD_LETTER_SUFFIX).partitions(partitions).replicas(1).build()

    /**
     * Separa as falhas nas duas únicas categorias que importam operacionalmente.
     *
     * **Transitória** — throttling do DynamoDB, conexão caída, timeout. Retentada no lugar, com
     * backoff exponencial, porque a próxima tentativa tem chance real de dar certo e o offset não
     * pode avançar sobre um evento que nunca foi aplicado.
     *
     * **Inprocessável** — [InvalidTransactionEventException]. Retentar não adianta: o payload vai
     * continuar igualmente malformado daqui a 500ms. Pior, retentar indefinidamente prenderia o
     * consumidor naquele offset e travaria todas as mensagens bem formadas que estivessem atrás
     * dele na mesma partição — um registro ruim derrubando uma partição inteira. Esses vão direto
     * para o dead letter topic, onde podem ser inspecionados e reprocessados depois que o produtor
     * for corrigido.
     */
    @Bean
    fun kafkaErrorHandler(kafkaTemplate: KafkaTemplate<*, *>): DefaultErrorHandler {
        val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate, ::deadLetterDestinationFor)

        // Limitado de propósito. Retentar para sempre transformaria a indisponibilidade de uma
        // dependência numa parada sem fim; depois de três tentativas espalhadas por alguns
        // segundos, o evento é posto em quarentena e o consumidor segue drenando a partição. O
        // Kafka retém a mensagem original de qualquer forma, então nada se perde e um replay
        // continua sempre possível.
        val backOff =
            ExponentialBackOff().apply {
                initialInterval = 500
                multiplier = 2.0
                maxInterval = 5_000
                maxAttempts = 3
                // Espalha os retries quando várias threads do consumidor falham no mesmo instante
                // — um throttle do DynamoDB costuma atingir todas de uma vez, e um backoff
                // idêntico faria todas retentarem em sincronia, voltando a estrangular a tabela a
                // cada onda.
                jitter = 100
            }

        return DefaultErrorHandler(recoverer, backOff).apply {
            addNotRetryableExceptions(InvalidTransactionEventException::class.java)
        }
    }
}
