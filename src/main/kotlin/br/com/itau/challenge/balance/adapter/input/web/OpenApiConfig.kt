package br.com.itau.challenge.balance.adapter.input.web

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun balanceApiDefinition(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("Balance Query API")
                .version("v1")
                .description(
                    """
                    Returns the most recent balance of an account.

                    Balances are projected from the `transacoes-financeiras-processadas` Kafka
                    topic, not computed by this service: each event carries the balance the
                    authorizer settled, and the newest event wins. Events are ordered by their
                    microsecond timestamp rather than by arrival, so a late or duplicated
                    message never rolls a balance backwards.
                    """.trimIndent(),
                ),
        )
}
