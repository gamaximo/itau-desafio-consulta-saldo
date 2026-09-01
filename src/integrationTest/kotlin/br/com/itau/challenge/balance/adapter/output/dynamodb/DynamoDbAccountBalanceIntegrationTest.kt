package br.com.itau.challenge.balance.adapter.output.dynamodb

import br.com.itau.challenge.balance.domain.model.AccountBalance
import br.com.itau.challenge.balance.domain.model.Money
import org.junit.jupiter.api.Test
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import java.math.BigDecimal
import java.net.URI
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercita a escrita condicional contra um DynamoDB real.
 *
 * Estes casos não podem ser provados com um cliente mockado: se `attribute_not_exists(...) OR
 * #version < :version` de fato rejeita uma versão igual é uma pergunta sobre a avaliação de
 * expressões do DynamoDB, não sobre este código. Um teste unitário que verifica a string da
 * expressão prova a string; só isto aqui prova o comportamento.
 *
 * Exige infraestrutura de verdade — rode com `make integration-test`.
 */
class DynamoDbAccountBalanceIntegrationTest {

    private val client: DynamoDbClient =
        DynamoDbClient
            .builder()
            .endpointOverride(URI.create(System.getenv("DYNAMODB_ENDPOINT") ?: "http://localhost:8000"))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
            .build()

    private val tableName = System.getenv("BALANCE_TABLE_NAME") ?: "AccountBalances"
    private val repository = DynamoDbAccountBalanceRepository(client, tableName)
    private val provider = DynamoDbAccountBalanceProvider(client, tableName)

    /** Uma conta nova por teste, para que os casos fiquem independentes da ordem de execução. */
    private fun newAccountId() = UUID.randomUUID().toString()

    private fun balance(
        accountId: String,
        amount: String,
        version: Long,
        transactionId: String = UUID.randomUUID().toString(),
    ) = AccountBalance(
        accountId = accountId,
        owner = UUID.randomUUID().toString(),
        balance = Money(BigDecimal(amount), "BRL"),
        lastTransactionId = transactionId,
        version = version,
    )

    @Test
    fun `grava e lê um saldo de volta`() {
        val accountId = newAccountId()

        assertTrue(repository.saveIfNewer(balance(accountId, "183.12", version = 1_000)))

        val stored = assertNotNull(provider.findByAccountId(accountId))
        assertEquals(BigDecimal("183.12"), stored.balance.amount)
        assertEquals(1_000, stored.version)
    }

    @Test
    fun `retorna null para uma conta nunca vista`() {
        assertNull(provider.findByAccountId(newAccountId()))
    }

    @Test
    fun `aplica uma versão mais nova`() {
        val accountId = newAccountId()
        repository.saveIfNewer(balance(accountId, "100.00", version = 1_000))

        assertTrue(repository.saveIfNewer(balance(accountId, "300.00", version = 2_000)))

        assertEquals(BigDecimal("300.00"), assertNotNull(provider.findByAccountId(accountId)).balance.amount)
    }

    /** Entrega fora de ordem: um evento atrasado não pode fazer o saldo retroceder. */
    @Test
    fun `rejeita uma versão mais antiga e deixa o saldo armazenado intacto`() {
        val accountId = newAccountId()
        repository.saveIfNewer(balance(accountId, "300.00", version = 2_000))

        assertFalse(repository.saveIfNewer(balance(accountId, "100.00", version = 1_000)))

        assertEquals(BigDecimal("300.00"), assertNotNull(provider.findByAccountId(accountId)).balance.amount)
    }

    /**
     * Idempotência, e a razão de a comparação ser estrita em vez de `<=`. Um evento reenviado
     * carrega uma versão idêntica, que não é *menor que* a armazenada, então é rejeitado sem
     * nenhuma tabela de deduplicação envolvida.
     */
    @Test
    fun `rejeita um reenvio byte a byte idêntico do mesmo evento`() {
        val accountId = newAccountId()
        val event = balance(accountId, "250.00", version = 5_000, transactionId = "fixed-transaction")

        assertTrue(repository.saveIfNewer(event))
        assertFalse(repository.saveIfNewer(event))
        assertFalse(repository.saveIfNewer(event))

        val stored = assertNotNull(provider.findByAccountId(accountId))
        assertEquals(BigDecimal("250.00"), stored.balance.amount)
        assertEquals(5_000, stored.version)
    }

    /**
     * A propriedade de convergência que faz todo o desenho funcionar: seja qual for a ordem em
     * que os eventos são aplicados, o estado final é o mais recente. Este teste embaralha os
     * mesmos três eventos e verifica que o resultado é idêntico todas as vezes.
     */
    @Test
    fun `converge para a versão mais recente independentemente da ordem de chegada`() {
        val versions = listOf(1_000L, 2_000L, 3_000L)

        versions.permutations().forEach { arrivalOrder ->
            val accountId = newAccountId()

            arrivalOrder.forEach { version ->
                repository.saveIfNewer(balance(accountId, "$version.00", version = version))
            }

            val stored = assertNotNull(provider.findByAccountId(accountId))
            assertEquals(3_000L, stored.version, "arrival order $arrivalOrder should still end at the newest version")
            assertEquals(BigDecimal("3000.00"), stored.balance.amount)
        }
    }

    @Test
    fun `preserva a precisão decimal num ciclo completo de escrita e leitura`() {
        val accountId = newAccountId()

        repository.saveIfNewer(balance(accountId, "0.07", version = 1))

        assertEquals(BigDecimal("0.07"), assertNotNull(provider.findByAccountId(accountId)).balance.amount)
    }

    private fun <T> List<T>.permutations(): List<List<T>> =
        if (size <= 1) {
            listOf(this)
        } else {
            flatMap { head ->
                (this - head).permutations().map { tail -> listOf(head) + tail }
            }
        }
}
