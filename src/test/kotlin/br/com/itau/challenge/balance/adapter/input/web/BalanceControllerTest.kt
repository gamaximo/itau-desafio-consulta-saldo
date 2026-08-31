package br.com.itau.challenge.balance.adapter.input.web

import br.com.itau.challenge.balance.domain.exception.AccountBalanceNotFoundException
import br.com.itau.challenge.balance.fixture.ACCOUNT_ID
import br.com.itau.challenge.balance.fixture.OWNER_ID
import br.com.itau.challenge.balance.fixture.accountBalance
import br.com.itau.challenge.balance.fixture.money
import br.com.itau.challenge.balance.port.input.GetAccountBalanceUseCase
import br.com.itau.challenge.balance.port.output.AccountBalanceProvider
import br.com.itau.challenge.balance.port.output.AccountBalanceRepository
import br.com.itau.challenge.balance.port.output.AccountBalanceStorageException
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

@SpringBootTest
@AutoConfigureMockMvc
class BalanceControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var getAccountBalanceUseCase: GetAccountBalanceUseCase

    // The driven ports are mocked away so the web slice never reaches DynamoDB, and the context
    // starts without any infrastructure behind it.
    @MockitoBean
    private lateinit var accountBalanceProvider: AccountBalanceProvider

    @MockitoBean
    private lateinit var accountBalanceRepository: AccountBalanceRepository

    @Test
    fun `returns the balance in the contracted shape`() {
        given(getAccountBalanceUseCase.getBalance(ACCOUNT_ID))
            .willReturn(accountBalance(balance = money(amount = "183.12")))

        mockMvc.get("/balances/$ACCOUNT_ID").andExpect {
            status { isOk() }
            content { contentType(MediaType.APPLICATION_JSON) }
            content {
                json(
                    """
                    {
                      "id": "$ACCOUNT_ID",
                      "owner": "$OWNER_ID",
                      "balance": { "amount": 183.12, "currency": "BRL" },
                      "updated_at": "2025-07-04T12:02:44.589998-03:00"
                    }
                    """,
                )
            }
        }
    }

    /**
     * The offset must be rendered, not assumed. A response of `15:02:44Z` would be the same
     * instant but a different contract from the one the challenge specifies, and a consumer
     * parsing it as local time would be three hours off.
     */
    @Test
    fun `renders updated_at with an explicit offset`() {
        given(getAccountBalanceUseCase.getBalance(ACCOUNT_ID)).willReturn(accountBalance())

        mockMvc.get("/balances/$ACCOUNT_ID").andExpect {
            status { isOk() }
            jsonPath("$.updated_at") { value("2025-07-04T12:02:44.589998-03:00") }
        }
    }

    /**
     * The fractional part must always be six digits. With the stock ISO formatter this case
     * renders as `.4848` and a whole second renders with no fraction at all, so a consumer
     * parsing a fixed layout breaks on whichever variant it meets second.
     */
    @Test
    fun `always renders six fractional digits`() {
        given(getAccountBalanceUseCase.getBalance(ACCOUNT_ID))
            .willReturn(accountBalance(version = 1_788_218_839_484_800))

        mockMvc.get("/balances/$ACCOUNT_ID").andExpect {
            status { isOk() }
            jsonPath("$.updated_at") { value("2026-08-31T20:27:19.484800-03:00") }
        }
    }

    /** A balance landing exactly on a whole second must still carry the fraction. */
    @Test
    fun `renders the fraction even for a whole second`() {
        given(getAccountBalanceUseCase.getBalance(ACCOUNT_ID))
            .willReturn(accountBalance(version = 1_751_641_364_000_000))

        mockMvc.get("/balances/$ACCOUNT_ID").andExpect {
            status { isOk() }
            jsonPath("$.updated_at") { value("2025-07-04T12:02:44.000000-03:00") }
        }
    }

    @Test
    fun `returns 404 as a problem detail when the account has no balance`() {
        given(getAccountBalanceUseCase.getBalance(ACCOUNT_ID))
            .willThrow(AccountBalanceNotFoundException(ACCOUNT_ID))

        mockMvc.get("/balances/$ACCOUNT_ID").andExpect {
            status { isNotFound() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.title") { value("Account balance not found") }
            jsonPath("$.accountId") { value(ACCOUNT_ID) }
        }
    }

    /**
     * A malformed identifier is rejected by the framework before the use case runs — which is
     * why this expectation does not stub anything.
     */
    @Test
    fun `returns 400 for an identifier that is not a UUID`() {
        mockMvc.get("/balances/not-a-uuid").andExpect {
            status { isBadRequest() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.title") { value("Malformed account identifier") }
        }
    }

    /**
     * 503, not 500: it tells the caller that retrying is the correct response. A generic 500
     * would push an operator to investigate this service when the fault is downstream.
     */
    @Test
    fun `returns 503 when the balance store is unavailable`() {
        given(getAccountBalanceUseCase.getBalance(ACCOUNT_ID))
            .willThrow(AccountBalanceStorageException("boom", RuntimeException("timeout")))

        mockMvc.get("/balances/$ACCOUNT_ID").andExpect {
            status { isServiceUnavailable() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.title") { value("Balance store unavailable") }
        }
    }

    /** The internal failure message must not reach the caller. */
    @Test
    fun `does not leak internal failure details`() {
        given(getAccountBalanceUseCase.getBalance(ACCOUNT_ID))
            .willThrow(AccountBalanceStorageException("dynamodb.eu-west-1.amazonaws.com timed out", RuntimeException()))

        mockMvc.get("/balances/$ACCOUNT_ID").andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.detail") { value("The balance store is temporarily unavailable, please retry") }
        }
    }

    @Test
    fun `rejects unsupported HTTP methods`() {
        mockMvc.post("/balances/$ACCOUNT_ID").andExpect {
            status { isMethodNotAllowed() }
        }
    }
}
