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
 * Exercises the conditional write against a real DynamoDB.
 *
 * These cases cannot be proven with a mocked client: whether `attribute_not_exists(...) OR
 * #version < :version` actually rejects an equal version is a question about DynamoDB's
 * expression evaluation, not about this code. A unit test asserting the expression string
 * proves the string; only this proves the behaviour.
 *
 * Requires live infrastructure — run with `make integration-test`.
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

    /** A fresh account per test, so the cases stay independent of execution order. */
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
    fun `stores and reads back a balance`() {
        val accountId = newAccountId()

        assertTrue(repository.saveIfNewer(balance(accountId, "183.12", version = 1_000)))

        val stored = assertNotNull(provider.findByAccountId(accountId))
        assertEquals(BigDecimal("183.12"), stored.balance.amount)
        assertEquals(1_000, stored.version)
    }

    @Test
    fun `returns null for an account that was never seen`() {
        assertNull(provider.findByAccountId(newAccountId()))
    }

    @Test
    fun `applies a newer version`() {
        val accountId = newAccountId()
        repository.saveIfNewer(balance(accountId, "100.00", version = 1_000))

        assertTrue(repository.saveIfNewer(balance(accountId, "300.00", version = 2_000)))

        assertEquals(BigDecimal("300.00"), assertNotNull(provider.findByAccountId(accountId)).balance.amount)
    }

    /** Out-of-order delivery: a late event must not roll the balance backwards. */
    @Test
    fun `rejects an older version and leaves the stored balance untouched`() {
        val accountId = newAccountId()
        repository.saveIfNewer(balance(accountId, "300.00", version = 2_000))

        assertFalse(repository.saveIfNewer(balance(accountId, "100.00", version = 1_000)))

        assertEquals(BigDecimal("300.00"), assertNotNull(provider.findByAccountId(accountId)).balance.amount)
    }

    /**
     * Idempotency, and the reason the comparison is strict rather than `<=`. A replayed event
     * carries an identical version, which is not *less than* the stored one, so it is rejected
     * with no dedup table involved.
     */
    @Test
    fun `rejects a byte-identical replay of the same event`() {
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
     * The convergence property that makes the whole design work: whatever order the events are
     * applied in, the end state is the newest one. This shuffles the same three events and
     * asserts the result is identical every time.
     */
    @Test
    fun `converges to the newest version regardless of arrival order`() {
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
    fun `preserves decimal precision through a full write and read cycle`() {
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
