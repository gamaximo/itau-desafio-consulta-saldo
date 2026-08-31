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
 * The generated contract is only useful if it is actually served. springdoc is built against
 * Spring Boot 3 and Jackson 2 while this application runs Boot 4 and Jackson 3, so this test
 * exists to fail loudly if that combination ever stops working — otherwise the documentation
 * would quietly 404 and nobody would notice until a consumer asked for it.
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
    fun `serves the OpenAPI document with the balance endpoint`() {
        mockMvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.paths['/balances/{accountId}'].get") { exists() }
        }
    }

    @Test
    fun `publishes the API identity in the document`() {
        mockMvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.info.title") { value("Balance Query API") }
            jsonPath("$.info.version") { value("v1") }
        }
    }

    @Test
    fun `documents the error responses a consumer has to handle`() {
        mockMvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.paths['/balances/{accountId}'].get.responses.200") { exists() }
            jsonPath("$.paths['/balances/{accountId}'].get.responses.404") { exists() }
            jsonPath("$.paths['/balances/{accountId}'].get.responses.503") { exists() }
        }
    }

    @Test
    fun `serves the Swagger UI`() {
        mockMvc.get("/swagger-ui/index.html").andExpect {
            status { isOk() }
        }
    }
}
