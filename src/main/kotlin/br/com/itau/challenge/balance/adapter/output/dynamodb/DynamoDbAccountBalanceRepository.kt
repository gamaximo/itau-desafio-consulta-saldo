package br.com.itau.challenge.balance.adapter.output.dynamodb

import br.com.itau.challenge.balance.domain.model.AccountBalance
import br.com.itau.challenge.balance.port.output.AccountBalanceRepository
import br.com.itau.challenge.balance.port.output.AccountBalanceStorageException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest

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

@Component
class DynamoDbAccountBalanceRepository(
    private val dynamoDbClient: DynamoDbClient,
    @Value("\${dynamodb.table-name}") private val tableName: String,
) : AccountBalanceRepository {

    override fun saveIfNewer(accountBalance: AccountBalance): Boolean {
        val request =
            PutItemRequest
                .builder()
                .tableName(tableName)
                .item(accountBalance.toItem())
                .conditionExpression(NEWER_VERSION_ONLY)
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
            true
        } catch (_: ConditionalCheckFailedException) {
            // Não é erro: o saldo armazenado já está na mesma versão ou à frente deste evento.
            // Engolir a exceção aqui — em vez de deixá-la chegar ao error handler do consumidor —
            // é o que impede duplicatas e entregas atrasadas de serem retentadas e, depois,
            // mandadas para o dead letter topic.
            //
            // Capturada antes de SdkException de propósito: ela é uma subclasse, e inverter a
            // ordem transformaria silenciosamente toda duplicata rejeitada numa falha de
            // infraestrutura retentável.
            false
        } catch (exception: SdkException) {
            throw AccountBalanceStorageException(
                "Falha ao gravar o saldo da conta '${accountBalance.accountId}'",
                exception,
            )
        }
    }
}
