package br.com.itau.challenge.balance.adapter.input.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val ACCESS_LOGGER = "br.com.itau.challenge.balance.Access"

class RequestLoggingFilterTest {

    private val filter = RequestLoggingFilter()
    private val appender = ListAppender<ILoggingEvent>()
    private lateinit var logger: Logger

    @BeforeEach
    fun capturarLogs() {
        logger = LoggerFactory.getLogger(ACCESS_LOGGER) as Logger
        appender.start()
        logger.addAppender(appender)
        logger.level = Level.INFO
    }

    @AfterEach
    fun soltarLogs() {
        logger.detachAppender(appender)
        appender.stop()
    }

    private fun executar(
        method: String = "GET",
        uri: String = "/balances/5b19c8b6-0cc4-4c72-a989-0c2ee15fa975",
        status: Int = 200,
    ): ILoggingEvent? {
        val request = MockHttpServletRequest(method, uri)
        val response = MockHttpServletResponse().apply { setStatus(status) }
        filter.doFilter(request, response, MockFilterChain())
        return appender.list.firstOrNull()
    }

    @Test
    fun `registra uma linha por requisicao atendida`() {
        val evento = assertNotNull(executar())

        assertTrue(evento.formattedMessage.contains("GET"))
        assertTrue(evento.formattedMessage.contains("/balances/5b19c8b6-0cc4-4c72-a989-0c2ee15fa975"))
        assertTrue(evento.formattedMessage.contains("200"))
    }

    /**
     * Os campos precisam estar no MDC, e não só no texto: é isso que os torna filtráveis no
     * agregador. Procurar "500" dentro de uma mensagem livre não é uma consulta confiável.
     */
    @Test
    fun `expoe metodo, rota, status e duracao como campos pesquisaveis`() {
        val evento = assertNotNull(executar(method = "GET", status = 503))

        assertEquals("GET", evento.mdcPropertyMap["http.method"])
        assertEquals("/balances/5b19c8b6-0cc4-4c72-a989-0c2ee15fa975", evento.mdcPropertyMap["http.path"])
        assertEquals("503", evento.mdcPropertyMap["http.status"])
        assertNotNull(evento.mdcPropertyMap["http.duration_ms"])
    }

    /**
     * As probes batem de segundos em segundos, para sempre. Registrá-las encheria o agregador de
     * linhas que ninguém consulta e ainda esconderia o tráfego real no meio do ruído.
     */
    @Test
    fun `nao registra as chamadas ao actuator`() {
        executar(uri = "/actuator/health/readiness")
        executar(uri = "/actuator/prometheus")

        assertTrue(appender.list.isEmpty(), "as probes não podem aparecer no log de acesso")

        executar(uri = "/balances/5b19c8b6-0cc4-4c72-a989-0c2ee15fa975")

        assertEquals(1, appender.list.size, "o tráfego real precisa aparecer")
    }

    /**
     * Threads são reaproveitadas entre requisições: sem limpar o MDC, o contexto de uma vazaria
     * para a seguinte e o log atribuiria a rota errada.
     */
    @Test
    fun `limpa o contexto apos a requisicao`() {
        executar()

        listOf("http.method", "http.path", "http.status", "http.duration_ms").forEach {
            assertFalse(org.slf4j.MDC.getCopyOfContextMap().orEmpty().containsKey(it), "$it vazou para a próxima requisição")
        }
    }
}
