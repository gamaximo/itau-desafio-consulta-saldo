package br.com.itau.challenge.balance.adapter.output.dynamodb

import br.com.itau.challenge.balance.domain.model.AccountBalance
import br.com.itau.challenge.balance.port.output.AccountBalanceProvider
import br.com.itau.challenge.balance.port.output.AccountBalanceStorageException
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
                // A strongly consistent read costs twice the capacity of an eventually
                // consistent one, and it is worth it here. Eventual consistency can serve a
                // balance from a replica that has not caught up — so a client that just saw a
                // transaction confirmed could query and get the *previous* balance back. For a
                // balance endpoint that reads as a bug in the bank, not as a stale cache.
                .consistentRead(true)
                .build()

        val item =
            try {
                dynamoDbClient.getItem(request)
            } catch (exception: SdkException) {
                throw AccountBalanceStorageException(
                    "Failed to read balance for account '$accountId'",
                    exception,
                )
            }

        // `hasItem()` distinguishes a missing item from an empty response; GetItem returns an
        // empty map rather than null when the key does not exist.
        return if (item.hasItem()) item.item().toAccountBalance() else null
    }
}
