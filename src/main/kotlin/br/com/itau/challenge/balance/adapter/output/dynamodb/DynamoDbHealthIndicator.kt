package br.com.itau.challenge.balance.adapter.output.dynamodb

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest

/** Chave reservada para a sonda. Nunca é gravada — o que importa é a chamada completar. */
private const val PROBE_KEY = "health-probe"

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
 * Usa um `GetItem` numa chave que sabidamente não existe, e **não** `DescribeTable`.
 *
 * `DescribeTable` parece a escolha natural, mas é uma operação de *control plane*: tem limite de
 * taxa muito mais baixo que o data plane e compartilhado por conta e região, não por tabela. Com
 * a probe batendo a cada poucos segundos, multiplicada pelas réplicas e por outras aplicações da
 * mesma conta, ela vira fonte de throttling — e o efeito é perverso, porque a verificação
 * derrubaria a readiness de instâncias saudáveis.
 *
 * Um `GetItem` de chave inexistente percorre o mesmo caminho que a aplicação usa de verdade,
 * consome capacidade mínima e ainda detecta tabela ausente, que responde com
 * `ResourceNotFoundException`.
 */
@Component("dynamoDb")
class DynamoDbHealthIndicator(
    private val dynamoDbClient: DynamoDbClient,
    @Value("\${dynamodb.table-name}") private val tableName: String,
) : HealthIndicator {

    override fun health(): Health {
        val request =
            GetItemRequest
                .builder()
                .tableName(tableName)
                .key(mapOf(ACCOUNT_ID_ATTRIBUTE to AttributeValue.builder().s(PROBE_KEY).build()))
                .build()

        return try {
            dynamoDbClient.getItem(request)

            Health
                .up()
                .withDetail("table", tableName)
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
