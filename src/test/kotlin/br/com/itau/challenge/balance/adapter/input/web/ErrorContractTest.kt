package br.com.itau.challenge.balance.adapter.input.web

import br.com.itau.challenge.balance.fixture.ACCOUNT_ID
import br.com.itau.challenge.balance.port.input.GetAccountBalanceUseCase
import br.com.itau.challenge.balance.port.output.AccountBalanceProvider
import br.com.itau.challenge.balance.port.output.AccountBalanceRepository
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertFalse

/**
 * Garante que a API fale **um único dialeto de erro**.
 *
 * Os handlers próprios já respondiam em RFC 7807, mas as exceções tratadas pelo próprio Spring —
 * rota inexistente, método não suportado — saíam no formato legado, e uma falha inesperada não
 * tinha tratamento algum. Um consumidor com parser de `problem+json` quebrava ao errar a URL.
 *
 * Estes testes fixam o contrato inteiro, não só os caminhos que anteciparmos.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ErrorContractTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var getAccountBalanceUseCase: GetAccountBalanceUseCase

    @MockitoBean
    private lateinit var accountBalanceProvider: AccountBalanceProvider

    @MockitoBean
    private lateinit var accountBalanceRepository: AccountBalanceRepository

    @Test
    fun `responde em problem+json quando a rota nao existe`() {
        mockMvc.get("/rota-que-nao-existe").andExpect {
            status { isNotFound() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
        }
    }

    /**
     * Também protege contra um efeito colateral do handler genérico: se ele "roubasse" as
     * exceções padrão do Spring MVC, este 405 viraria 500 silenciosamente.
     */
    @Test
    fun `responde em problem+json quando o metodo http nao e suportado`() {
        mockMvc.post("/balances/$ACCOUNT_ID").andExpect {
            status { isMethodNotAllowed() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
        }
    }

    @Test
    fun `responde 500 em problem+json diante de uma falha inesperada`() {
        given(getAccountBalanceUseCase.getBalance(ACCOUNT_ID))
            .willThrow(IllegalStateException("estado impossivel"))

        mockMvc.get("/balances/$ACCOUNT_ID").andExpect {
            status { isInternalServerError() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.title") { value("Internal error") }
            jsonPath("$.detail") { value("An unexpected error occurred") }
        }
    }

    /**
     * O caso que justifica a mensagem genérica: a causa real pode carregar nome de host interno,
     * credencial em query string ou trecho de payload de outro cliente. Nada disso pode
     * atravessar a fronteira HTTP — vai para o log, onde tem dono e controle de acesso.
     */
    @Test
    fun `nao vaza detalhes internos numa falha inesperada`() {
        given(getAccountBalanceUseCase.getBalance(ACCOUNT_ID))
            .willThrow(IllegalStateException("falha em db-prod-01.interno:5432 com senha=hunter2"))

        val corpo =
            mockMvc
                .get("/balances/$ACCOUNT_ID")
                .andExpect { status { isInternalServerError() } }
                .andReturn()
                .response.contentAsString

        assertFalse(corpo.contains("db-prod-01"), "o corpo não pode expor infraestrutura interna")
        assertFalse(corpo.contains("hunter2"), "o corpo não pode expor credenciais")
        assertFalse(corpo.contains("IllegalStateException"), "o corpo não pode expor tipos internos")
    }

    /**
     * O handler genérico não pode ofuscar os tratamentos específicos: um UUID malformado
     * continua sendo 400, e não 500.
     */
    @Test
    fun `mantem o tratamento especifico de identificador malformado`() {
        mockMvc.get("/balances/nao-e-uuid").andExpect {
            status { isBadRequest() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.title") { value("Malformed account identifier") }
        }
    }
}
