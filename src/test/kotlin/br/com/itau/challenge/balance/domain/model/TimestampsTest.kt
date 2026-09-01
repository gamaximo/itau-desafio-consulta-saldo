package br.com.itau.challenge.balance.domain.model

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class TimestampsTest {

    @Test
    fun `converte microssegundos epoch para instant sem perder precisão`() {
        val result = microsToInstant(1751641364589998L)

        assertEquals(1751641364L, result.epochSecond)
        assertEquals(589_998_000, result.nano)
        assertEquals(Instant.parse("2025-07-04T15:02:44.589998Z"), result)
    }

    @Test
    fun `converte o próprio epoch`() {
        assertEquals(Instant.EPOCH, microsToInstant(0))
    }

    /**
     * Divisão com piso, não truncamento em direção a zero. Com `/` e `%`, um timestamp anterior
     * ao epoch produziria um ajuste negativo de nanossegundos e o `Instant` o rejeitaria — ou,
     * pior, em outro caminho de código, cairia silenciosamente um segundo fora.
     */
    @Test
    fun `converte um timestamp anterior ao epoch`() {
        val result = microsToInstant(-1_500_000L)

        assertEquals(Instant.parse("1969-12-31T23:59:58.500Z"), result)
    }
}
