package br.com.itau.challenge.balance.adapter.input.web

import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * A rede de segurança: qualquer falha que não tenha tratamento específico em lugar nenhum.
 *
 * Sem isto, um defeito nosso — um NPE, um estado impossível — escaparia para o tratamento padrão
 * do Spring e sairia no formato legado, tornando a resposta imprevisível justamente no caso em
 * que o cliente mais precisa de previsibilidade. É, por definição, a "situação inesperada" — e
 * ela também merece um contrato.
 *
 * ### Por que vive num advice separado, e por que a ordem importa
 *
 * `@ExceptionHandler(Exception::class)` casa com *tudo*. Se estivesse junto dos handlers
 * específicos, que precisam de [Ordered.HIGHEST_PRECEDENCE] para vencer o handler do Spring MVC,
 * ele capturaria também as exceções do próprio framework — e um "método não suportado" viraria
 * 500 em vez de 405.
 *
 * Isolado aqui com [Ordered.LOWEST_PRECEDENCE], ele é o último a ser consultado: só recebe o que
 * ninguém mais quis. A ordem entre os dois advices é o que mantém cada erro no seu status
 * correto, e `ErrorContractTest` verifica as duas pontas.
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
class UnexpectedErrorHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(exception: Exception): ProblemDetail {
        // ERROR com o stack trace completo, porque aqui ninguém sabe o que aconteceu: ao
        // contrário do 503, isto não é uma falha esperada de dependência, é algo que não foi
        // previsto e que alguém precisa investigar.
        logger.error("Falha inesperada ao processar a requisição", exception)

        return ProblemDetail
            .forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                // Mensagem deliberadamente vaga: a causa real pode conter nome de host interno,
                // credencial em query string ou trecho de payload de outro cliente, e nada disso
                // pode atravessar a fronteira HTTP.
                "An unexpected error occurred",
            ).apply { title = "Internal error" }
    }
}
