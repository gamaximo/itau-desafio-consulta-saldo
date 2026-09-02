package br.com.itau.challenge.balance.adapter.output.dynamodb

import br.com.itau.challenge.balance.domain.model.AccountBalance
import br.com.itau.challenge.balance.port.output.AccountBalanceProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest

@Component
class DynamoDbAccountBalanceProvider(
    private val dynamoDbClient: DynamoDbClient,
    @Value("\${dynamodb.table-name}") private val tableName: String,
) : AccountBalanceProvider {

    override fun findByAccountId(accountId: String): AccountBalance? {
        val request =
            GetItemRequest
                .builder()
                .tableName(tableName)
                .key(
                    mapOf(
                        ACCOUNT_ID_ATTRIBUTE to AttributeValue.builder().s(accountId).build(),
                    ),
                )
                // Uma leitura fortemente consistente custa o dobro da capacidade de uma leitura
                // eventualmente consistente, e aqui vale a pena. Com consistência eventual, o
                // saldo pode ser servido por uma réplica que ainda não recebeu a última escrita —
                // então um cliente que acabou de ver uma transação confirmada poderia consultar e
                // receber o saldo *anterior*. Num endpoint de saldo, isso é lido como um defeito
                // do banco, não como cache desatualizado.
                .consistentRead(true)
                .build()

        val item =
            try {
                dynamoDbClient.getItem(request)
            } catch (exception: SdkException) {
                throw exception.toStorageFailure("Falha ao ler o saldo da conta '$accountId'")
            }

        // `hasItem()` distingue item ausente de resposta vazia; o GetItem devolve um mapa vazio,
        // e não null, quando a chave não existe.
        return if (item.hasItem()) item.item().toAccountBalance() else null
    }
}
