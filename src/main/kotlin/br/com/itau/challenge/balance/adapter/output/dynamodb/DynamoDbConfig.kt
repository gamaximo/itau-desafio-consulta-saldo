package br.com.itau.challenge.balance.adapter.output.dynamodb

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import java.net.URI
import java.time.Duration

@Configuration
class DynamoDbConfig {

    @Bean
    fun dynamoDbClient(
        @Value("\${dynamodb.endpoint}") endpoint: String,
        @Value("\${dynamodb.region}") region: String,
        @Value("\${dynamodb.api-call-timeout-ms}") apiCallTimeoutMs: Long,
        @Value("\${dynamodb.api-call-attempt-timeout-ms}") apiCallAttemptTimeoutMs: Long,
    ): DynamoDbClient =
        DynamoDbClient
            .builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")),
            ).overrideConfiguration(
                ClientOverrideConfiguration
                    .builder()
                    // Os dois timeouts são definidos explicitamente porque o padrão do SDK para
                    // `apiCallTimeout` é *ilimitado*. Sem ele, uma partição do DynamoDB que para
                    // de responder — sem derrubar a conexão — prende uma thread do Tomcat para
                    // sempre; algumas dessas e a API inteira deixa de atender, health check
                    // incluído, por causa de uma dependência que está apenas degradada.
                    //
                    // apiCallAttemptTimeout limita uma tentativa HTTP isolada; apiCallTimeout
                    // limita a chamada inteira, incluindo os retries do próprio SDK. Manter o
                    // primeiro bem abaixo do segundo é o que deixa espaço para esses retries
                    // realmente acontecerem.
                    .apiCallAttemptTimeout(Duration.ofMillis(apiCallAttemptTimeoutMs))
                    .apiCallTimeout(Duration.ofMillis(apiCallTimeoutMs))
                    // A política de retry padrão do SDK (3 tentativas, backoff exponencial com
                    // jitter, apenas para throttling e erros transitórios) é mantida
                    // deliberadamente — ela já implementa o padrão de retry/backoff corretamente,
                    // e uma rejeição de escrita condicional não é um erro retentável, então nunca
                    // é retentada por engano.
                    .build(),
            ).build()
}
