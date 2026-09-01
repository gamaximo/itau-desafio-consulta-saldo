package br.com.itau.challenge.balance.adapter.output.dynamodb

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest

/**
 * Reporta se a tabela de saldos está acessível, para que a probe de **readiness** reflita a
 * realidade.
 *
 * Sem isto, a aplicação continuaria respondendo `UP` com o DynamoDB completamente fora, e o
 * orquestrador seguiria mandando tráfego para uma instância que só sabe devolver 503. A separação
 * entre liveness e readiness só tem valor se algo de fato alimentar a readiness — caso contrário
 * são duas probes dizendo a mesma coisa.
 *
 * Deliberadamente **fora** do grupo de liveness: perder o banco não é motivo para o contêiner ser
 * morto e reiniciado. Reiniciar não conserta uma dependência externa; só troca uma instância
 * degradada por uma instância fria igualmente degradada.
 *
 * Usa `DescribeTable` em vez de uma leitura real: verifica conectividade, credenciais e existência
 * da tabela sem consumir capacidade de leitura da tabela nem depender de um item específico.
 */
@Component("dynamoDb")
class DynamoDbHealthIndicator(
    private val dynamoDbClient: DynamoDbClient,
    @Value("\${dynamodb.table-name}") private val tableName: String,
) : HealthIndicator {

    override fun health(): Health {
        val request = DescribeTableRequest.builder().tableName(tableName).build()

        return try {
            val status = dynamoDbClient.describeTable(request).table().tableStatusAsString()

            Health
                .up()
                .withDetail("table", tableName)
                .withDetail("tableStatus", status)
                .build()
        } catch (exception: SdkException) {
            // A exceção entra no detalhe porque o endpoint de health é interno, não é o contrato
            // público: aqui a informação serve a quem opera, e ocultá-la só dificultaria o
            // diagnóstico. O que nunca pode vazar é pela API de saldo, e isso o
            // BalanceExceptionHandler garante.
            Health
                .down(exception)
                .withDetail("table", tableName)
                .build()
        }
    }
}
