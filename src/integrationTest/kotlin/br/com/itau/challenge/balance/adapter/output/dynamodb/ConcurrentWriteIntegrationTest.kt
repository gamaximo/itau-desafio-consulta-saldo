package br.com.itau.challenge.balance.adapter.output.dynamodb

import br.com.itau.challenge.balance.domain.model.AccountBalance
import br.com.itau.challenge.balance.domain.model.Money
import br.com.itau.challenge.balance.domain.model.RejectionReason
import org.junit.jupiter.api.Test
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import java.math.BigDecimal
import java.net.URI
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private const val THREADS = 16

/**
 * Prova a atomicidade da escrita condicional sob **corrida real**.
 *
 * Os demais testes aplicam os eventos em sequência, variando a ordem. Isso demonstra convergência,
 * mas não atomicidade: nenhum deles jamais coloca duas escritas disputando o mesmo item no mesmo
 * instante, que é exatamente o cenário do enunciado — "dois débitos chegando no mesmo momento".
 *
 * Aqui 16 threads sincronizadas por uma barreira gravam versões diferentes da mesma conta
 * simultaneamente. Se a condição não fosse avaliada dentro da escrita, pelo lado do servidor,
 * duas delas leriam o mesmo estado e a última a gravar venceria — deixando o saldo numa versão
 * arbitrária em vez da mais recente.
 *
 * Exige infraestrutura de verdade — rode com `make integration-test`.
 */
class ConcurrentWriteIntegrationTest {

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

    private fun balance(
        accountId: String,
        version: Long,
    ) = AccountBalance(
        accountId = accountId,
        owner = UUID.randomUUID().toString(),
        balance = Money(BigDecimal(version), "BRL"),
        lastTransactionId = UUID.randomUUID().toString(),
        version = version,
    )

    @Test
    fun `sob escrita concorrente a versao mais recente vence`() {
        val accountId = UUID.randomUUID().toString()
        val versoes = (1..THREADS).map { it * 1_000L }
        val barreira = CyclicBarrier(THREADS)
        val pool = Executors.newFixedThreadPool(THREADS)

        try {
            // A barreira faz as threads partirem juntas. Sem ela, o agendamento do sistema
            // operacional serializaria boa parte das escritas e o teste passaria sem nunca ter
            // criado a disputa que se quer provar.
            val resultados =
                pool.invokeAll(
                    versoes.map { versao ->
                        Callable {
                            barreira.await(10, TimeUnit.SECONDS)
                            repository.saveIfNewer(balance(accountId, versao))
                        }
                    },
                ).map { it.get(30, TimeUnit.SECONDS) }

            val armazenado = assertNotNull(provider.findByAccountId(accountId))

            assertEquals(
                versoes.max(),
                armazenado.version,
                "com escritas simultâneas, o saldo final deve ser o da maior versão",
            )
            assertEquals(BigDecimal(versoes.max()).setScale(2), armazenado.balance.amount)

            // Toda escrita recusada foi classificada como fora de ordem, e nenhuma como
            // duplicata: as versões são todas distintas. Uma duplicata aqui indicaria erro na
            // classificação.
            assertEquals(
                emptyList(),
                resultados.filter { it == RejectionReason.DUPLICATE },
                "nenhuma versão se repete, então nenhuma recusa pode ser por duplicidade",
            )
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * A outra face da corrida: todas as threads gravando a **mesma** versão. Exatamente uma pode
     * vencer, e as demais precisam ser classificadas como duplicata — que é o comportamento de
     * idempotência sob concorrência, e não sob replay sequencial.
     */
    @Test
    fun `sob escrita concorrente do mesmo evento apenas uma vence`() {
        val accountId = UUID.randomUUID().toString()
        val barreira = CyclicBarrier(THREADS)
        val pool = Executors.newFixedThreadPool(THREADS)

        try {
            val resultados =
                pool.invokeAll(
                    (1..THREADS).map {
                        Callable {
                            barreira.await(10, TimeUnit.SECONDS)
                            repository.saveIfNewer(balance(accountId, version = 7_000L))
                        }
                    },
                ).map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(1, resultados.count { it == null }, "exatamente uma escrita pode ser aplicada")
            assertEquals(
                THREADS - 1,
                resultados.count { it == RejectionReason.DUPLICATE },
                "as demais são o mesmo evento, então devem ser recusadas como duplicata",
            )
        } finally {
            pool.shutdownNow()
        }
    }
}
