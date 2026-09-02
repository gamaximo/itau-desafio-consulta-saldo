package br.com.itau.challenge.balance.adapter.input.kafka

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.stereotype.Component

/**
 * Reporta se o broker está alcançável — o único sinal de que a **ingestão** continua viva.
 *
 * Sem isto existe um ponto cego: com o Kafka fora, a API segue respondendo consultas normalmente,
 * a readiness continua `UP`, e nada indica que o serviço parou de receber transações. Os saldos
 * simplesmente congelam, e quanto mais tempo passa mais desatualizada fica a resposta — enquanto
 * o health afirma que está tudo bem.
 *
 * ### Por que fica fora das probes
 *
 * Deliberadamente **não** entra em readiness nem em liveness, que incluem apenas os componentes
 * listados no `application.yaml`.
 *
 * Derrubar a readiness aqui seria contraproducente: a API de consulta continua perfeitamente
 * funcional sem o Kafka, porque lê do DynamoDB. Tirar a instância do balanceamento por causa de
 * uma dependência que só afeta a ingestão degradaria o serviço que ainda funciona — trocando um
 * problema por dois. E derrubar a liveness seria pior ainda: o contêiner seria reiniciado em
 * ciclo enquanto o broker estivesse fora, sem que isso ajudasse em nada.
 *
 * O lugar certo é o health geral, que serve à monitoração: quem observa vê a degradação e é
 * alertado, sem que o orquestrador tome nenhuma decisão a partir disso.
 */
@Component("kafka")
class KafkaHealthIndicator(
    private val kafkaAdmin: KafkaAdmin,
    @Value("\${transactions.topic-name}") private val topicName: String,
) : HealthIndicator {

    override fun health(): Health =
        try {
            // Descrever o próprio tópico consumido, em vez de apenas pingar o cluster: verifica de
            // uma vez que o broker responde e que o tópico existe. Um tópico apagado por engano
            // produz o mesmo sintoma de um broker fora — ingestão parada — e merece o mesmo alarme.
            val descricao = kafkaAdmin.describeTopics(topicName)[topicName]

            Health
                .up()
                .withDetail("topic", topicName)
                .withDetail("partitions", descricao?.partitions()?.size ?: 0)
                .build()
        } catch (exception: Exception) {
            Health
                .down(exception)
                .withDetail("topic", topicName)
                .build()
        }
}
