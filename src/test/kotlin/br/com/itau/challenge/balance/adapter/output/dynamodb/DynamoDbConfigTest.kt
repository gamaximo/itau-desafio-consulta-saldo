package br.com.itau.challenge.balance.adapter.output.dynamodb

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

class DynamoDbConfigTest {

    /**
     * A construção do cliente é onde um `apiCallTimeout` ilimitado passaria despercebido: o SDK
     * aceita a configuração de qualquer jeito e só revela a diferença durante uma indisponibilidade.
     * Este teste ao menos fixa que a configuração de override é montada sem erro.
     */
    @Test
    fun `constrói o cliente com timeouts explícitos`() {
        val client =
            DynamoDbConfig().dynamoDbClient(
                endpoint = "http://localhost:8000",
                region = "us-east-1",
                apiCallTimeoutMs = 3_000,
                apiCallAttemptTimeoutMs = 1_000,
            )

        assertNotNull(client)
        client.close()
    }
}
