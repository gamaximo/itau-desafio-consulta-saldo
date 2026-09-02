package br.com.itau.challenge.balance.adapter.input.kafka

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.stereotype.Component

private const val DEAD_LETTER_SUFFIX = ".DLT"

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

    private val deadLetterName = topicName + DEAD_LETTER_SUFFIX

    override fun health(): Health {
        // Um tópico por chamada, e não os dois de uma vez: `describeTopics` falha inteiro quando
        // qualquer um dos nomes não existe, e a exceção não diz qual. Perguntando separadamente, o
        // alarme aponta o tópico exato — a diferença entre "o Kafka caiu" e "alguém apagou o DLT",
        // que pedem ações completamente diferentes.
        val indisponiveis = listOf(topicName, deadLetterName).filterNot { existe(it) }

        return when (indisponiveis.size) {
            0 ->
                Health
                    .up()
                    .withDetail("topic", topicName)
                    .withDetail("deadLetterTopic", deadLetterName)
                    .build()

            // Nenhum dos dois responde: quase sempre é o broker fora, e não dois tópicos apagados
            // no mesmo instante.
            2 ->
                Health
                    .down()
                    .withDetail("motivo", "o broker não respondeu ou nenhum dos tópicos existe")
                    .withDetail("topicosIndisponiveis", indisponiveis)
                    .build()

            else ->
                Health
                    .down()
                    .withDetail("motivo", "tópico ausente: a ingestão para, ou trava no primeiro evento inválido")
                    .withDetail("topicosIndisponiveis", indisponiveis)
                    .build()
        }
    }

    private fun existe(nome: String): Boolean =
        runCatching { kafkaAdmin.describeTopics(nome).containsKey(nome) }.getOrDefault(false)
}
