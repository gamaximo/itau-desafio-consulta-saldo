package br.com.itau.challenge.balance.port.output

/**
 * The balance store could not be reached or refused the operation for an infrastructural
 * reason — throttling, timeout, connection failure.
 *
 * It lives in `port` rather than in `domain` because unavailability is a property of the
 * contract between the core and the outside world, not a business rule. Declaring it here is
 * what lets the driven adapter translate `SdkException` into something the rest of the
 * application understands, so neither the use cases nor the web adapter ever import an AWS
 * type. Swapping DynamoDB for another store changes which exception is caught, not a single
 * line of the code that reacts to it.
 */
class AccountBalanceStorageException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)
