package br.com.itau.challenge.balance.adapter.input.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

private const val ACTUATOR_PREFIX = "/actuator"

/**
 * Registra uma linha por requisição atendida: método, rota, status e duração.
 *
 * Sem isto não existe trilha por requisição — só métricas agregadas. E agregado não responde a
 * pergunta que aparece numa investigação: "este sistema chamou às 14h32? o que ele recebeu?".
 * Para uma API consumida por outros sistemas, essa é a diferença entre conseguir e não conseguir
 * reconstruir o que aconteceu.
 *
 * Um filtro próprio em vez do access log do Tomcat porque este passa pelo SLF4J e sai no mesmo
 * formato estruturado do resto — enquanto o do Tomcat escreve num arquivo à parte, em texto, fora
 * do agregador.
 *
 * Os campos vão para o MDC, e não interpolados na mensagem, para virarem colunas pesquisáveis:
 * `http.status:500` é uma consulta; procurar "500" dentro de um texto livre não é.
 */
@Component
class RequestLoggingFilter : OncePerRequestFilter() {

    private val accessLogger = LoggerFactory.getLogger("br.com.itau.challenge.balance.Access")

    /**
     * As probes de liveness e readiness batem de segundos em segundos, para sempre. Registrá-las
     * encheria o agregador de linhas que ninguém consulta e ainda esconderia o tráfego real.
     */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI.startsWith(ACTUATOR_PREFIX)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val startedAt = System.nanoTime()

        try {
            filterChain.doFilter(request, response)
        } finally {
            // No finally: uma requisição que termina em exceção é justamente a que mais precisa
            // aparecer no log de acesso.
            val durationMs = (System.nanoTime() - startedAt) / 1_000_000

            MDC.put("http.method", request.method)
            MDC.put("http.path", request.requestURI)
            MDC.put("http.status", response.status.toString())
            MDC.put("http.duration_ms", durationMs.toString())
            // Origem da chamada: sem isto o log responde "o que aconteceu" mas não "quem fez".
            // `X-Forwarded-For` vem antes de `remoteAddr` porque atrás de um balanceador este
            // último seria sempre o IP do próprio balanceador, igual para todo mundo.
            MDC.put("client.ip", request.getHeader("X-Forwarded-For") ?: request.remoteAddr)
            request.getHeader("User-Agent")?.let { MDC.put("client.user_agent", it) }
            try {
                accessLogger.info(
                    "{} {} -> {} ({}ms)",
                    request.method,
                    request.requestURI,
                    response.status,
                    durationMs,
                )
            } finally {
                // Threads são reaproveitadas entre requisições: sem a limpeza, o contexto de uma
                // vazaria para a seguinte e o log apontaria para a conta errada.
                //
                // `account` e `owner` são postos pelo controller, não aqui, mas a limpeza é deste
                // filtro porque ele é a fronteira da requisição — se cada camada limpasse o que
                // põe, o controller teria de limpar antes de retornar e os campos sumiriam
                // exatamente quando esta linha vai ser escrita.
                listOf(
                    "http.method",
                    "http.path",
                    "http.status",
                    "http.duration_ms",
                    "client.ip",
                    "client.user_agent",
                    "account",
                    "owner",
                ).forEach(MDC::remove)
            }
        }
    }
}
