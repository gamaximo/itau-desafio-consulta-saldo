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

    // As portas de saída são mockadas para que a fatia web nunca chegue ao DynamoDB, e o
    // contexto suba sem nenhuma infraestrutura por trás.
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
     * O offset precisa ser renderizado, não presumido. Uma resposta `15:02:44Z` seria o mesmo
     * instante, mas um contrato diferente do que o desafio especifica, e um consumidor que a
     * interpretasse como horário local erraria por três horas.
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
     * A parte fracionária precisa ter sempre seis dígitos. Com o formatador ISO padrão, este caso
     * sai como `.4848` e um segundo cheio sai sem fração nenhuma, então um consumidor que
     * interprete um layout fixo quebra na variante que encontrar por segundo.
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

    /** Um saldo que caia exatamente num segundo cheio ainda precisa carregar a fração. */
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
     * Um identificador malformado é rejeitado pelo framework antes de o caso de uso rodar — por
     * isso esta expectativa não faz stub de nada.
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
     * 503, e não 500: informa a quem chamou que retentar é a resposta correta. Um 500 genérico
     * levaria um operador a investigar este serviço quando a falha está na dependência.
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

    /** A mensagem interna de falha não pode chegar a quem chamou. */
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
