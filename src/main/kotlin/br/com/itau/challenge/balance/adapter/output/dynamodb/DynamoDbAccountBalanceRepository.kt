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
 * The condition that makes the whole ingestion safe.
 *
 * A `PutItem` carrying this expression is applied only when the account has never been seen,
 * or when the stored version is **strictly** older than the incoming one. DynamoDB evaluates
 * it inside the write, on the partition that owns the item, so it is atomic even when several
 * consumer instances hit the same account at the same microsecond.
 *
 * Because the comparison is strict, it handles three cases with one rule:
 *  - **out-of-order** — a late event carries an older version and is rejected;
 *  - **duplicate** — a replayed event carries the *same* version, which is not `<`, so it is
 *    rejected too. That is idempotency, with no separate dedup table to keep and expire;
 *  - **concurrent** — of two writes racing for the same account, the older one loses
 *    regardless of which reaches the partition first.
 *
 * Attribute names are aliased through `#` placeholders because both `version` and `owner` are
 * DynamoDB reserved words and would otherwise fail to parse.
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
            // Not an error: the stored balance is already at or ahead of this event. Swallowing
            // it here — rather than letting it reach the consumer's error handler — is what
            // keeps duplicates and late deliveries from being retried and then dead-lettered.
            //
            // Caught before SdkException on purpose: it is a subclass, and reversing the order
            // would silently turn every rejected duplicate into a retryable infrastructure
            // failure.
            false
        } catch (exception: SdkException) {
            throw AccountBalanceStorageException(
                "Failed to store balance for account '${accountBalance.accountId}'",
                exception,
            )
        }
    }
}
