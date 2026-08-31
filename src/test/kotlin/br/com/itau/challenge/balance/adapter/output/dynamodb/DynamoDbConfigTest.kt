package br.com.itau.challenge.balance.adapter.output.dynamodb

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

class DynamoDbConfigTest {

    /**
     * Building the client is where an unbounded `apiCallTimeout` would go unnoticed: the SDK
     * accepts the configuration either way and only reveals the difference during an outage.
     * This at least pins that the override configuration is assembled without error.
     */
    @Test
    fun `builds a client with explicit timeouts`() {
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
