package br.com.itau.challenge.balance.adapter.input.web

import br.com.itau.challenge.balance.domain.exception.AccountBalanceNotFoundException
import br.com.itau.challenge.balance.port.output.AccountBalanceStorageException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * Turns every failure into RFC 7807 `application/problem+json`.
 *
 * The API is consumed by other systems, so an error has to be as machine-readable as a
 * success. A caller must be able to tell "this account does not exist" (never retry) from
 * "the store is unavailable" (retry shortly) from "your identifier is malformed" (fix the
 * request) — from the status code alone, without parsing prose.
 *
 * Internal details never cross this boundary: a stack trace or an AWS error message would leak
 * infrastructure topology to whoever calls the endpoint. They go to the logs, where they
 * belong, and the caller gets a stable, neutral message.
 */
@RestControllerAdvice
class BalanceExceptionHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(AccountBalanceNotFoundException::class)
    fun handleNotFound(exception: AccountBalanceNotFoundException): ProblemDetail =
        // Deliberately not logged as an error: querying an account with no projected balance
        // is an ordinary outcome, and logging it would bury real failures under noise that any
        // caller can trigger at will.
        ProblemDetail
            .forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                "No balance found for the requested account",
            ).apply {
                title = "Account balance not found"
                setProperty("accountId", exception.accountId)
            }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleMalformedAccountId(exception: MethodArgumentTypeMismatchException): ProblemDetail =
        ProblemDetail
            .forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "The account identifier must be a valid UUID",
            ).apply {
                title = "Malformed account identifier"
                setProperty("parameter", exception.name)
            }

    @ExceptionHandler(AccountBalanceStorageException::class)
    fun handleStorageUnavailable(exception: AccountBalanceStorageException): ProblemDetail {
        logger.error("Balance store unavailable", exception)

        // 503 rather than 500: the request was valid and the failure is transient, which tells
        // the caller that retrying is the correct response instead of alerting a human.
        return ProblemDetail
            .forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The balance store is temporarily unavailable, please retry",
            ).apply { title = "Balance store unavailable" }
    }
}
