package br.com.itau.challenge.balance.adapter.input.web

import br.com.itau.challenge.balance.domain.exception.AccountBalanceNotFoundException
import br.com.itau.challenge.balance.port.output.AccountBalanceStorageException
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * Converte toda falha em `application/problem+json` (RFC 7807).
 *
 * A API é consumida por outros sistemas, então um erro precisa ser tão legível por máquina quanto
 * um sucesso. Quem chama tem que conseguir distinguir "esta conta não existe" (nunca retente) de
 * "o armazenamento está indisponível" (retente em breve) de "seu identificador está malformado"
 * (corrija a requisição) — apenas pelo status code, sem interpretar texto.
 *
 * Detalhes internos nunca cruzam esta fronteira: um stack trace ou uma mensagem da AWS vazaria a
 * topologia da infraestrutura para quem chama o endpoint. Esses vão para os logs, que é o lugar
 * deles, e quem chama recebe uma mensagem estável e neutra.
 */
/**
 * Precedência máxima porque o Spring MVC também trata algumas destas exceções quando
 * `spring.mvc.problemdetails` está ligado — `MethodArgumentTypeMismatchException` entre elas.
 * Sem esta ordenação, o handler genérico do framework venceria e o cliente receberia uma
 * mensagem sobre conversão de tipo em vez de "o identificador precisa ser um UUID válido".
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class BalanceExceptionHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(AccountBalanceNotFoundException::class)
    fun handleNotFound(exception: AccountBalanceNotFoundException): ProblemDetail =
        // Deliberadamente não logado como erro: consultar uma conta sem saldo projetado é um
        // desfecho comum, e logar isso soterraria falhas reais sob ruído que qualquer chamador
        // pode provocar à vontade.
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
        logger.error("Armazenamento de saldos indisponível", exception)

        // 503 em vez de 500: a requisição era válida e a falha é transitória, o que informa a quem
        // chamou que retentar é a resposta correta, em vez de acionar um humano.
        return ProblemDetail
            .forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The balance store is temporarily unavailable, please retry",
            ).apply { title = "Balance store unavailable" }
    }
}
