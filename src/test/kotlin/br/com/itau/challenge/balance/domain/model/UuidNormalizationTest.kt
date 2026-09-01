package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import br.com.itau.challenge.balance.fixture.ACCOUNT_ID
import br.com.itau.challenge.balance.fixture.account
import br.com.itau.challenge.balance.fixture.processedTransaction
import br.com.itau.challenge.balance.fixture.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Identificadores são guardados na forma canônica, e não como vieram no payload.
 *
 * Não é preciosismo: `accountId` é a partition key, e o controller converte o path para `UUID`
 * antes de consultar — o que sempre produz minúsculas e sem abreviações. Guardar o texto cru
 * significava que um evento com `BCCECBE8-…` era gravado em maiúsculas e **nunca mais
 * encontrado**: a consulta procurava `bccecbe8-…` e devolvia 404 para um saldo que existia no
 * banco.
 *
 * O defeito era silencioso dos dois lados — a ingestão reportava sucesso e a API reportava conta
 * inexistente.
 */
class UuidNormalizationTest {

    private val maiusculo = ACCOUNT_ID.uppercase()

    @Test
    fun `normaliza o identificador da conta para minusculas`() {
        assertEquals(ACCOUNT_ID, account(id = maiusculo).id)
    }

    @Test
    fun `normaliza o identificador do titular`() {
        assertEquals(ACCOUNT_ID, account(owner = maiusculo).owner)
    }

    @Test
    fun `normaliza o identificador da transacao`() {
        assertEquals(ACCOUNT_ID, transaction(id = maiusculo).id)
    }

    /**
     * `UUID.fromString` aceita formas abreviadas: `1-1-1-1-1` é um UUID válido para o Java e
     * expande para `00000001-0001-0001-0001-000000000001`. Sem normalizar, escrita e leitura
     * usariam grafias diferentes da mesma chave.
     */
    @Test
    fun `expande a forma abreviada que o Java aceita`() {
        assertEquals("00000001-0001-0001-0001-000000000001", account(id = "1-1-1-1-1").id)
    }

    /** A projeção precisa carregar a forma canônica até a chave gravada no banco. */
    @Test
    fun `a projecao usa o identificador canonico`() {
        val balance =
            processedTransaction(
                account = account(id = maiusculo, owner = maiusculo),
                transaction = transaction(id = maiusculo),
            ).toAccountBalance()

        assertEquals(ACCOUNT_ID, balance.accountId)
        assertEquals(ACCOUNT_ID, balance.owner)
        assertEquals(ACCOUNT_ID, balance.lastTransactionId)
    }

    @Test
    fun `continua rejeitando o que nao e UUID`() {
        assertFailsWith<InvalidTransactionEventException> { account(id = "nao-e-uuid") }
    }

    /** Duas grafias do mesmo identificador descrevem a mesma conta. */
    @Test
    fun `contas com grafias diferentes do mesmo id sao iguais`() {
        assertEquals(account(id = ACCOUNT_ID), account(id = maiusculo))
        assertEquals(account(id = ACCOUNT_ID).hashCode(), account(id = maiusculo).hashCode())
    }

    @Test
    fun `transacoes com grafias diferentes do mesmo id sao iguais`() {
        assertEquals(transaction(id = ACCOUNT_ID), transaction(id = maiusculo))
        assertEquals(transaction(id = ACCOUNT_ID).hashCode(), transaction(id = maiusculo).hashCode())
    }
}
