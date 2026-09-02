package br.com.itau.challenge.balance.adapter.input.kafka

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import br.com.itau.challenge.balance.port.output.AccountBalanceStorageException
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
import org.springframework.util.backoff.BackOff
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
        "Evento inprocessável enviado ao dead letter topic: tópico={} partição={} offset={} motivo={}",
        record.topic(),
        record.partition(),
        record.offset(),
        rootCauseOf(exception),
    )
    return TopicPartition(record.topic() + DEAD_LETTER_SUFFIX, ANY_PARTITION)
}

/**
 * A mensagem da causa mais profunda, e não a do wrapper.
 *
 * O Spring Kafka envolve a falha numa `ListenerExecutionFailedException`, cuja mensagem diz
 * apenas qual método lançou a exceção — algo que já se sabe. A informação que resolve o problema
 * ("Unknown transaction type 'TRANSFER'") está na causa aninhada. Quem abre este log às três da
 * manhã precisa do porquê, não da assinatura do listener.
 */
private fun rootCauseOf(exception: Throwable): String? {
    var causa: Throwable = exception
    // `cause !== causa` evita laço infinito numa exceção que aponte para si mesma como causa.
    while (causa.cause != null && causa.cause !== causa) {
        causa = causa.cause!!
    }
    return causa.message
}

/**
 * Backoff curto e finito: para falhas sem perspectiva de melhora sozinha, como um defeito no
 * código. Depois destas tentativas o evento é quarentenado e o consumidor segue.
 */
private fun defaultBackOff(): ExponentialBackOff =
    ExponentialBackOff().apply {
        initialInterval = 500
        multiplier = 2.0
        maxInterval = 5_000
        maxAttempts = 3
        jitter = 100
    }

/**
 * Backoff longo para indisponibilidade da dependência: espera crescendo até 30s, insistindo por
 * até 30 minutos no total.
 *
 * **Longo, e não infinito.** Retentar para sempre parece a resposta certa — a dependência volta,
 * o backlog é drenado — mas cria um modo de falha pior que o problema: se a causa nunca melhorar,
 * a partição fica parada indefinidamente e sem alarme próprio, porque durante os retries o Spring
 * Kafka registra as tentativas apenas em DEBUG. O sintoma seria um lag crescendo sem nenhuma
 * linha de log explicando.
 *
 * Trinta minutos cobrem com folga o que se espera de uma indisponibilidade real — deploy,
 * failover, throttling prolongado — e ainda assim garantem que a partição volte a andar. Passado
 * esse tempo o evento é quarentenado no dead letter topic, onde é visível e reprocessável.
 *
 * O jitter é maior aqui porque uma indisponibilidade atinge todas as threads ao mesmo tempo, e sem
 * dispersão elas voltariam a martelar o banco em ondas sincronizadas no momento em que ele tenta
 * se recuperar.
 */
private fun storageBackOff(): ExponentialBackOff =
    ExponentialBackOff().apply {
        initialInterval = 1_000
        multiplier = 2.0
        maxInterval = 30_000
        maxElapsedTime = 30 * 60 * 1_000L
        jitter = 1_000
    }

/**
 * A política de espera para uma falha, escolhida pelo tipo dela.
 *
 * Função nomeada em vez de lambda dentro do bean para poder ser testada diretamente — a diferença
 * entre desistir e não desistir é a regra mais consequente deste arquivo, e ela ficaria coberta
 * apenas por um teste de integração com o banco fora do ar.
 */
internal fun backOffFor(exception: Throwable): BackOff =
    if (exception.isCausedByStorageUnavailability()) storageBackOff() else defaultBackOff()

/** A causa raiz importa: o Spring Kafka envolve a exceção do listener antes de entregá-la aqui. */
private fun Throwable.isCausedByStorageUnavailability(): Boolean =
    generateSequence(this) { atual -> atual.cause?.takeIf { it !== atual } }
        .any { it is AccountBalanceStorageException }

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
     * Separa as falhas em três categorias, cada uma com uma política própria.
     *
     * **Indisponibilidade do armazenamento** — [AccountBalanceStorageException]: throttling,
     * conexão caída, timeout. Retentada por até **30 minutos**, com espera crescendo até 30s. É o
     * comportamento correto para um evento que só falha porque a dependência está fora: o offset
     * não avança, o lag cresce, e quando o banco volta o backlog é drenado sozinho.
     *
     * Um limite aqui seria uma armadilha silenciosa. Com backoff finito, uma indisponibilidade de
     * poucos segundos manda **transações válidas** para o dead letter topic, e recuperá-las passa
     * a exigir intervenção manual — enquanto o saldo daquelas contas fica desatualizado sem que
     * nada acuse. Foi exatamente o que aconteceu num teste: 35 segundos de banco fora bastaram
     * para dois eventos legítimos serem quarentenados.
     *
     * O preço é a partição parar de avançar durante a indisponibilidade. É o preço certo: o Kafka
     * existe para ser esse buffer, e o sintoma — lag crescendo — é visível, alertável e se resolve
     * sozinho quando a dependência volta. O limite de 30 minutos existe para que "parar de
     * avançar" nunca vire "parar para sempre".
     *
     * **Inprocessável** — [InvalidTransactionEventException]: payload malformado, enum
     * desconhecido, timestamp implausível. Retentar não adianta, e insistir prenderia o consumidor
     * naquele offset, travando as mensagens boas atrás dele na mesma partição. Vai direto ao dead
     * letter topic.
     *
     * **Qualquer outra** — um defeito nosso, um estado impossível. Backoff curto e finito, depois
     * o dead letter topic. Retentar para sempre por causa de um bug travaria a partição sem
     * previsão de melhora, que é justamente o que o caso da indisponibilidade tem e este não.
     */
    @Bean
    fun kafkaErrorHandler(kafkaTemplate: KafkaTemplate<*, *>): DefaultErrorHandler {
        val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate, ::deadLetterDestinationFor)

        return DefaultErrorHandler(recoverer, defaultBackOff()).apply {
            addNotRetryableExceptions(InvalidTransactionEventException::class.java)
            // A política passa a depender do tipo da falha: sem isto, um único backoff finito
            // valeria tanto para um bug quanto para o banco estar fora, e os dois têm perspectivas
            // completamente diferentes de melhorar sozinhos.
            setBackOffFunction { _, exception -> backOffFor(exception) }
        }
    }
}
