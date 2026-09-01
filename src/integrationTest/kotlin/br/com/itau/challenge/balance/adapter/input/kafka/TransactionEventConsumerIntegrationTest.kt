package br.com.itau.challenge.balance.adapter.input.kafka

import br.com.itau.challenge.balance.port.output.AccountBalanceProvider
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercita o listener real contra um broker real e um DynamoDB real: uma mensagem é publicada e
 * nada é invocado diretamente — o que se verifica é que a aplicação em execução a consumiu e
 * projetou o saldo por conta própria.
 *
 * Exige infraestrutura de verdade — rode com `make integration-test`.
 */
@SpringBootTest
class TransactionEventConsumerIntegrationTest(
    @Autowired private val kafkaTemplate: KafkaTemplate<String, String>,
    @Autowired private val accountBalanceProvider: AccountBalanceProvider,
    @Autowired private val consumerFactory: ConsumerFactory<String, String>,
    @Value("\${transactions.topic-name}") private val topic: String,
) {
    private fun event(
        accountId: String,
        amount: String,
        timestamp: Long,
        transactionId: String = UUID.randomUUID().toString(),
        type: String = "CREDIT",
    ) = """
        {
          "transaction": {
            "id": "$transactionId",
            "type": "$type",
            "amount": 10.00,
            "currency": "BRL",
            "status": "APPROVED",
            "timestamp": $timestamp
          },
          "account": {
            "id": "$accountId",
            "owner": "${UUID.randomUUID()}",
            "created_at": 1634874339000000,
            "status": "ENABLED",
            "balance": { "amount": $amount, "currency": "BRL" }
          }
        }
        """.trimIndent()

    /** Faz polling até obter um valor ou estourar o prazo, para que o teste nunca durma às cegas. */
    private fun <T> awaitValue(
        timeout: Duration = Duration.ofSeconds(30),
        supplier: () -> T?,
    ): T? {
        val deadline = System.currentTimeMillis() + timeout.toMillis()
        while (System.currentTimeMillis() < deadline) {
            supplier()?.let { return it }
            Thread.sleep(250)
        }
        return null
    }

    @Test
    fun `consome um evento publicado e projeta o saldo`() {
        val accountId = UUID.randomUUID().toString()

        kafkaTemplate.send(topic, event(accountId, amount = "183.12", timestamp = 1_000_000))

        val stored = awaitValue { accountBalanceProvider.findByAccountId(accountId) }

        assertEquals(BigDecimal("183.12"), assertNotNull(stored).balance.amount)
    }

    /**
     * A versão ponta a ponta da garantia de ordenação: três eventos para uma única conta,
     * publicados do mais novo para o mais antigo e sem chave Kafka — portanto espalhados entre
     * partições e consumidos concorrentemente. Ainda assim o saldo tem que assentar no mais
     * recente.
     */
    @Test
    fun `assenta no evento mais recente mesmo com outros mais antigos chegando depois`() {
        val accountId = UUID.randomUUID().toString()

        kafkaTemplate.send(topic, event(accountId, amount = "300.00", timestamp = 3_000_000))
        kafkaTemplate.send(topic, event(accountId, amount = "100.00", timestamp = 1_000_000))
        kafkaTemplate.send(topic, event(accountId, amount = "200.00", timestamp = 2_000_000))

        val stored = awaitValue { accountBalanceProvider.findByAccountId(accountId) }
        assertNotNull(stored)

        // Dá espaço para os dois eventos mais antigos serem consumidos antes de verificar que
        // não mudaram nada; caso contrário a verificação passaria apenas porque eles ainda não
        // tinham chegado.
        Thread.sleep(2_000)

        val settled = assertNotNull(accountBalanceProvider.findByAccountId(accountId))
        assertEquals(BigDecimal("300.00"), settled.balance.amount)
        assertEquals(3_000_000, settled.version)
    }

    @Test
    fun `ignora um evento duplicado`() {
        val accountId = UUID.randomUUID().toString()
        val transactionId = UUID.randomUUID().toString()
        val payload = event(accountId, amount = "250.00", timestamp = 5_000_000, transactionId = transactionId)

        repeat(3) { kafkaTemplate.send(topic, payload) }

        val stored = assertNotNull(awaitValue { accountBalanceProvider.findByAccountId(accountId) })
        assertEquals(BigDecimal("250.00"), stored.balance.amount)
        assertEquals(transactionId, stored.lastTransactionId)
    }

    /**
     * Um payload inprocessável precisa chegar ao dead letter topic sem nunca tocar o saldo, e sem
     * travar a partição em que chegou.
     */
    @Test
    fun `manda um evento inprocessável ao DLT e deixa o saldo intacto`() {
        val accountId = UUID.randomUUID().toString()
        val marker = UUID.randomUUID().toString()
        val invalid = event(accountId, amount = "999.99", timestamp = 9_000_000, transactionId = marker, type = "TRANSFER")

        kafkaTemplate.send(topic, invalid)

        val deadLettered =
            awaitValue {
                consumeDeadLetters().firstOrNull { it.contains(marker) }
            }

        assertNotNull(deadLettered, "expected the invalid event to reach $topic.DLT")
        assertTrue(deadLettered.contains("TRANSFER"))
        assertNull(accountBalanceProvider.findByAccountId(accountId), "an invalid event must not project a balance")
    }

    private fun consumeDeadLetters(): List<String> {
        // Um group id descartável a cada chamada, para que toda leitura comece do início do
        // tópico em vez de retomar um offset commitado. O `auto-offset-reset=earliest` vem da
        // própria configuração de consumidor da aplicação.
        val consumer =
            consumerFactory.createConsumer("dlt-assertions-${UUID.randomUUID()}", null).apply {
                subscribe(listOf("$topic.DLT"))
            }

        return consumer.use { it.poll(Duration.ofSeconds(2)).map { record -> record.value() } }
    }
}
