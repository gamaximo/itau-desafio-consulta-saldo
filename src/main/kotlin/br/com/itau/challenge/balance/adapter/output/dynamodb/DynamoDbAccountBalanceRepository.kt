package br.com.itau.challenge.balance.adapter.output.dynamodb

import br.com.itau.challenge.balance.domain.model.AccountBalance
import br.com.itau.challenge.balance.domain.model.RejectionReason
import br.com.itau.challenge.balance.port.output.AccountBalanceRepository
import br.com.itau.challenge.balance.port.output.AccountBalanceStorageException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure

/**
 * A condição que torna toda a ingestão segura.
 *
 * Um `PutItem` com esta expressão só é aplicado quando a conta nunca foi vista, ou quando a
 * versão armazenada é **estritamente** mais antiga que a versão que está chegando. O DynamoDB
 * avalia a condição dentro da escrita, na partição dona do item, então ela é atômica mesmo com
 * várias instâncias do consumidor gravando a mesma conta no mesmo microssegundo.
 *
 * Por ser uma comparação estrita, ela trata três casos com uma única regra:
 *  - **fora de ordem** — um evento atrasado carrega uma versão mais antiga e é rejeitado;
 *  - **duplicata** — um evento reenviado carrega a *mesma* versão, que não é `<`, então também é
 *    rejeitado. Isso é idempotência, sem nenhuma tabela de deduplicação para manter e expirar;
 *  - **concorrência** — de duas escritas disputando a mesma conta, a mais antiga perde,
 *    independentemente de qual chegar primeiro à partição.
 *
 * Os nomes de atributo passam por placeholders `#` porque tanto `version` quanto `owner` são
 * palavras reservadas do DynamoDB e, sem isso, a expressão nem seria interpretada.
 */
private const val NEWER_VERSION_ONLY =
    "attribute_not_exists(#accountId) OR #version < :version"

/**
 * Classifica a recusa comparando a versão armazenada com a que chegou.
 *
 * Se o item não vier junto da exceção — o DynamoDB não garante devolvê-lo em toda situação —
 * assume-se [RejectionReason.OUT_OF_ORDER], que é o caso mais comum num tópico sem chave de
 * partição. A alternativa seria uma leitura extra só para classificar uma métrica, o que não se
 * paga.
 */
private fun rejectionReasonFrom(
    exception: ConditionalCheckFailedException,
    incomingVersion: Long,
): RejectionReason {
    val storedVersion =
        exception
            .takeIf { it.hasItem() }
            ?.item()
            ?.get(VERSION_ATTRIBUTE)
            ?.n()
            ?.toLongOrNull()

    return if (storedVersion == incomingVersion) RejectionReason.DUPLICATE else RejectionReason.OUT_OF_ORDER
}

@Component
class DynamoDbAccountBalanceRepository(
    private val dynamoDbClient: DynamoDbClient,
    @Value("\${dynamodb.table-name}") private val tableName: String,
) : AccountBalanceRepository {

    override fun saveIfNewer(accountBalance: AccountBalance): RejectionReason? {
        val request =
            PutItemRequest
                .builder()
                .tableName(tableName)
                .item(accountBalance.toItem())
                .conditionExpression(NEWER_VERSION_ONLY)
                // Pede o item de volta quando a condição falha. Sem isto saberíamos apenas que a
                // escrita foi recusada, e não se foi uma repetição exata ou um evento atrasado —
                // dois casos com causas e alarmes diferentes. O DynamoDB devolve o item na própria
                // resposta de erro, então não custa uma leitura extra.
                .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                .expressionAttributeNames(
                    mapOf(
                        "#accountId" to ACCOUNT_ID_ATTRIBUTE,
                        "#version" to VERSION_ATTRIBUTE,
                    ),
                ).expressionAttributeValues(
                    mapOf(
                        ":version" to
                            AttributeValue
                                .builder()
                                .n(accountBalance.version.toString())
                                .build(),
                    ),
                ).build()

        return try {
            dynamoDbClient.putItem(request)
            null
        } catch (exception: ConditionalCheckFailedException) {
            // Não é erro: o saldo armazenado já está na mesma versão ou à frente deste evento.
            // Engolir a exceção aqui — em vez de deixá-la chegar ao error handler do consumidor —
            // é o que impede duplicatas e entregas atrasadas de serem retentadas e, depois,
            // mandadas para o dead letter topic.
            //
            // Capturada antes de SdkException de propósito: ela é uma subclasse, e inverter a
            // ordem transformaria silenciosamente toda duplicata rejeitada numa falha de
            // infraestrutura retentável.
            rejectionReasonFrom(exception, accountBalance.version)
        } catch (exception: SdkException) {
            throw AccountBalanceStorageException(
                "Falha ao gravar o saldo da conta '${accountBalance.accountId}'",
                exception,
            )
        }
    }
}
