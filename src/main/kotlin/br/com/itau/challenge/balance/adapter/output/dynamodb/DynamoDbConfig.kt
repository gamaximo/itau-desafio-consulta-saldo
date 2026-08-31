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
                    // Both timeouts are set explicitly because the SDK default for
                    // `apiCallTimeout` is *unbounded*. Without it, a DynamoDB partition that
                    // stops answering — without resetting the connection — parks a Tomcat
                    // worker thread forever; enough of those and the whole API stops serving,
                    // health check included, over a dependency that is only degraded.
                    //
                    // apiCallAttemptTimeout bounds a single HTTP attempt, apiCallTimeout bounds
                    // the call including the SDK's own retries. Keeping the former well under
                    // the latter is what leaves room for those retries to actually happen.
                    .apiCallAttemptTimeout(Duration.ofMillis(apiCallAttemptTimeoutMs))
                    .apiCallTimeout(Duration.ofMillis(apiCallTimeoutMs))
                    // The SDK's default retry strategy (3 attempts, exponential backoff with
                    // jitter, only on throttling and transient errors) is kept deliberately —
                    // it already implements the retry/backoff pattern correctly, and a
                    // conditional-write rejection is not a retryable error, so it is never
                    // retried by mistake.
                    .build(),
            ).build()
}
