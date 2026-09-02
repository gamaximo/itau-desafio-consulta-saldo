package br.com.itau.challenge.balance.adapter.output.dynamodb

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import br.com.itau.challenge.balance.port.output.AccountBalanceStorageException
import org.junit.jupiter.api.Test
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A classificação decide por quanto tempo a partição fica ocupada.
 *
 * Um erro do serviço melhora sozinho e merece o retry longo. Um erro da requisição — item grande
 * demais, atributo inválido — vai falhar exatamente igual daqui a 30 minutos, porque o defeito
 * está no que enviamos. Tratá-los igual significa prender a partição por meia hora retentando
 * algo impossível, com as mensagens boas esperando atrás.
 */
class StorageFailuresTest {

    private fun erroComStatus(status: Int): DynamoDbException =
        DynamoDbException
            .builder()
            .message("erro simulado")
            .statusCode(status)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("ValidationException").build())
            .build() as DynamoDbException

    @Test
    fun `falha de rede e tratada como transitoria`() {
        val falha = SdkClientException.builder().message("connection reset").build().toStorageFailure("ao gravar")

        assertTrue(falha is AccountBalanceStorageException, "sem resposta do serviço, não há o que julgar: é transitório")
    }

    @Test
    fun `erro do servidor e tratado como transitorio`() {
        assertTrue(erroComStatus(500).toStorageFailure("ao gravar") is AccountBalanceStorageException)
        assertTrue(erroComStatus(503).toStorageFailure("ao gravar") is AccountBalanceStorageException)
    }

    @Test
    fun `throttling e tratado como transitorio`() {
        val throttling =
            ProvisionedThroughputExceededException.builder().message("slow down").statusCode(400).build()

        assertTrue(
            throttling.toStorageFailure("ao gravar") is AccountBalanceStorageException,
            "throttling é 4xx, mas melhora sozinho: retentar é exatamente a resposta certa",
        )
    }

    @Test
    fun `limite de requisicoes e tratado como transitorio`() {
        val limite = RequestLimitExceededException.builder().message("too many requests").statusCode(400).build()

        assertTrue(limite.toStorageFailure("ao gravar") is AccountBalanceStorageException)
    }

    /**
     * Tabela ausente é 4xx, mas é infraestrutura: pode ser recriada, e aí o retry resolve sozinho.
     * Mandar ao dead letter topic exigiria reprocessamento manual de algo que se conserta.
     */
    @Test
    fun `tabela ausente e tratada como transitoria`() {
        val ausente = ResourceNotFoundException.builder().message("table not found").statusCode(400).build()

        assertTrue(ausente.toStorageFailure("ao gravar") is AccountBalanceStorageException)
    }

    /**
     * O caso que motivou tudo: um erro de requisição ocupava a partição por até 30 minutos
     * retentando algo que jamais funcionaria. Foi assim que o *number overflow* se comportava
     * antes de ser barrado no domínio.
     */
    @Test
    fun `erro de requisicao vai direto ao dead letter topic`() {
        val falha = erroComStatus(400).toStorageFailure("ao gravar o saldo")

        assertTrue(falha is InvalidTransactionEventException, "a mesma requisição falharia igual daqui a 30 minutos")
        assertTrue(falha.message!!.contains("rejeitou a requisição"))
        assertTrue(falha.message!!.contains("ao gravar o saldo"), "a mensagem preserva o contexto de quem chamou")
    }

    @Test
    fun `preserva a causa nas falhas transitorias`() {
        val original = SdkClientException.builder().message("timeout").build()

        val falha = original.toStorageFailure("ao ler") as AccountBalanceStorageException

        assertEquals(original, falha.cause)
        assertEquals("ao ler", falha.message)
    }
}
