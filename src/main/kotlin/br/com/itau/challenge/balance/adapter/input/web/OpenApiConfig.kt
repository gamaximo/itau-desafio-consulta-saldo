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
                    Retorna o saldo mais atual de uma conta.

                    Os saldos são projetados a partir do tópico Kafka
                    `transacoes-financeiras-processadas`, e não calculados por este serviço: cada
                    evento carrega o saldo que o autorizador liquidou, e o evento mais recente
                    prevalece. Os eventos são ordenados pelo timestamp em microssegundos, e não
                    pela ordem de chegada, de modo que uma mensagem atrasada ou duplicada nunca
                    faz um saldo retroceder.
                    """.trimIndent(),
                ),
        )
}
