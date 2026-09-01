package br.com.itau.challenge.balance.adapter.input.web

import br.com.itau.challenge.balance.port.output.AccountBalanceProvider
import br.com.itau.challenge.balance.port.output.AccountBalanceRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * O contrato gerado só serve se for de fato servido. O springdoc é construído sobre Spring Boot 3
 * e Jackson 2, enquanto esta aplicação roda Boot 4 e Jackson 3, então este teste existe para
 * falhar de forma barulhenta se essa combinação parar de funcionar — caso contrário a
 * documentação passaria a devolver 404 em silêncio e ninguém perceberia até um consumidor pedir
 * por ela.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocumentationTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var accountBalanceProvider: AccountBalanceProvider

    @MockitoBean
    private lateinit var accountBalanceRepository: AccountBalanceRepository

    @Test
    fun `serve o documento OpenAPI com o endpoint de saldo`() {
        mockMvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.paths['/balances/{accountId}'].get") { exists() }
        }
    }

    @Test
    fun `publica a identidade da API no documento`() {
        mockMvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.info.title") { value("Balance Query API") }
            jsonPath("$.info.version") { value("v1") }
        }
    }

    @Test
    fun `documenta as respostas de erro que um consumidor precisa tratar`() {
        mockMvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.paths['/balances/{accountId}'].get.responses.200") { exists() }
            jsonPath("$.paths['/balances/{accountId}'].get.responses.404") { exists() }
            jsonPath("$.paths['/balances/{accountId}'].get.responses.503") { exists() }
        }
    }

    @Test
    fun `serve a Swagger UI`() {
        mockMvc.get("/swagger-ui/index.html").andExpect {
            status { isOk() }
        }
    }
}
