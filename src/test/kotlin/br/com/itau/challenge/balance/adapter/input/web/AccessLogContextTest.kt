package br.com.itau.challenge.balance.adapter.input.web

import br.com.itau.challenge.balance.domain.exception.AccountBalanceNotFoundException
import br.com.itau.challenge.balance.fixture.ACCOUNT_ID
import br.com.itau.challenge.balance.fixture.OWNER_ID
import br.com.itau.challenge.balance.fixture.accountBalance
import br.com.itau.challenge.balance.port.input.GetAccountBalanceUseCase
import br.com.itau.challenge.balance.port.output.AccountBalanceProvider
import br.com.itau.challenge.balance.port.output.AccountBalanceRepository
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val ACCESS_LOGGER = "br.com.itau.challenge.balance.Access"

/**
 * Verifica a colaboração entre o controller e o filtro de acesso.
 *
 * O filtro só enxerga a URL, e a URL carrega apenas o identificador da conta; o titular só é
 * conhecido depois que o saldo é lido. Quem preenche o MDC é o controller, quem escreve a linha é
 * o filtro — e nenhum teste unitário dos dois isoladamente provaria que a informação atravessa
 * essa fronteira na ordem certa.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccessLogContextTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var getAccountBalanceUseCase: GetAccountBalanceUseCase

    @MockitoBean
    private lateinit var accountBalanceProvider: AccountBalanceProvider

    @MockitoBean
    private lateinit var accountBalanceRepository: AccountBalanceRepository

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

    private fun ultimaLinha() = appender.list.lastOrNull()

    @Test
    fun `registra conta e titular na consulta bem-sucedida`() {
        given(getAccountBalanceUseCase.getBalance(ACCOUNT_ID)).willReturn(accountBalance())

        mockMvc.get("/balances/$ACCOUNT_ID").andExpect { }

        val evento = assertNotNull(ultimaLinha())
        assertEquals(ACCOUNT_ID, evento.mdcPropertyMap["account"])
        assertEquals(OWNER_ID, evento.mdcPropertyMap["owner"])
        assertEquals("200", evento.mdcPropertyMap["http.status"])
    }

    /**
     * Os nomes de campo são os mesmos usados na ingestão, e é isso que permite uma consulta única
     * por `owner` devolver eventos consumidos e chamadas à API do mesmo titular.
     */
    @Test
    fun `usa os mesmos nomes de campo da ingestao`() {
        given(getAccountBalanceUseCase.getBalance(ACCOUNT_ID)).willReturn(accountBalance())

        mockMvc.get("/balances/$ACCOUNT_ID").andExpect { }

        val campos = assertNotNull(ultimaLinha()).mdcPropertyMap.keys
        assertEquals(true, campos.containsAll(listOf("account", "owner")))
    }

    /**
     * Sem conta encontrada não há titular a registrar. O campo simplesmente não aparece — melhor
     * que um `null` ou um vazio, que sugeririam um titular desconhecido em vez de inexistente.
     */
    @Test
    fun `nao inventa titular quando a conta nao existe`() {
        given(getAccountBalanceUseCase.getBalance(ACCOUNT_ID))
            .willThrow(AccountBalanceNotFoundException(ACCOUNT_ID))

        mockMvc.get("/balances/$ACCOUNT_ID").andExpect { }

        val evento = assertNotNull(ultimaLinha())
        assertEquals("404", evento.mdcPropertyMap["http.status"])
        assertNull(evento.mdcPropertyMap["owner"])
    }

    /** Num identificador malformado o controller nem chega a rodar, então não há o que registrar. */
    @Test
    fun `nao registra contexto de conta num identificador malformado`() {
        mockMvc.get("/balances/nao-e-uuid").andExpect { }

        val evento = assertNotNull(ultimaLinha())
        assertEquals("400", evento.mdcPropertyMap["http.status"])
        assertNull(evento.mdcPropertyMap["account"])
        assertNull(evento.mdcPropertyMap["owner"])
    }

    /**
     * Threads são reaproveitadas: se o contexto de uma requisição vazasse, a linha seguinte
     * atribuiria a consulta ao titular errado — um defeito que só apareceria sob carga.
     */
    @Test
    fun `nao vaza o titular de uma requisicao para a seguinte`() {
        given(getAccountBalanceUseCase.getBalance(ACCOUNT_ID)).willReturn(accountBalance())
        mockMvc.get("/balances/$ACCOUNT_ID").andExpect { }

        mockMvc.get("/balances/nao-e-uuid").andExpect { }

        assertNull(assertNotNull(ultimaLinha()).mdcPropertyMap["owner"])
    }
}
