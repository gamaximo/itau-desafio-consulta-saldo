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
 * Any partition of the dead letter topic will do — the original partition carries no meaning
 * once a message is quarantined, and pinning it would make the DLT fail whenever it has fewer
 * partitions than the source topic.
 */
private const val ANY_PARTITION = -1

private val deadLetterLogger = LoggerFactory.getLogger("br.com.itau.challenge.balance.DeadLetter")

/**
 * Where a rejected record goes, and the one place it is logged.
 *
 * A named function rather than a lambda inside the bean definition, so the routing rule and its
 * log line can be tested directly instead of only through a running Kafka container.
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
     * Topics are declared as beans so `KafkaAdmin` creates them at startup through the admin
     * API. Redpanda has `auto_create_topics_enabled` turned off in this stack, so without this
     * the application would start, subscribe to nothing, and sit there looking healthy while
     * consuming zero messages. Creation is idempotent: an existing topic is left untouched,
     * partition count included.
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
     * Splits failures into the only two categories that matter operationally.
     *
     * **Transient** — DynamoDB throttling, a dropped connection, a timeout. Retried in place
     * with exponential backoff, because the next attempt has a real chance of succeeding and
     * the offset must not advance past an event that was never applied.
     *
     * **Unprocessable** — [InvalidTransactionEventException]. Retrying is pointless: the
     * payload will be just as malformed in 500ms. Worse, retrying forever would park the
     * consumer on that offset and stall every well-formed message queued behind it on the same
     * partition — one bad record taking down a whole partition. These go straight to the dead
     * letter topic, where they can be inspected and replayed after the producer is fixed.
     */
    @Bean
    fun kafkaErrorHandler(kafkaTemplate: KafkaTemplate<*, *>): DefaultErrorHandler {
        val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate, ::deadLetterDestinationFor)

        // Bounded on purpose. Retrying forever would turn a dependency outage into an
        // unbounded stall; after three attempts spanning a few seconds, the event is
        // quarantined and the consumer keeps draining the partition. Kafka retains the
        // original message either way, so nothing is lost and a replay is always possible.
        val backOff =
            ExponentialBackOff().apply {
                initialInterval = 500
                multiplier = 2.0
                maxInterval = 5_000
                maxAttempts = 3
                // Spreads retries apart when many consumer threads fail at the same moment —
                // a DynamoDB throttle typically hits all of them at once, and identical
                // backoff would have them all retry in lockstep, re-throttling the table on
                // every wave.
                jitter = 100
            }

        return DefaultErrorHandler(recoverer, backOff).apply {
            addNotRetryableExceptions(InvalidTransactionEventException::class.java)
        }
    }
}
