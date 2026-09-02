package br.com.itau.challenge.balance.adapter.input.kafka

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val GRUPO_PRODUCAO = "balance-transaction-consumer"

/**
 * A proteção existe contra um erro humano de consequência desproporcional.
 *
 * Reprocessar significa subir uma instância a mais com outro `group.id`. Se alguém esquecer de
 * trocá-lo, essa instância entra no grupo que está servindo, dispara um rebalanceamento e passa a
 * disputar as partições com quem atende produção — e nada falha: o consumo continua, só o lag e a
 * latência denunciariam.
 */
class ReplayModeGuardTest {

    @Test
    fun `recusa subir em modo replay com o group-id de producao`() {
        val erro =
            assertFailsWith<IllegalArgumentException> {
                ReplayModeGuard(groupId = GRUPO_PRODUCAO, productionGroupId = GRUPO_PRODUCAO)
            }

        assertTrue(erro.message!!.contains("disputaria as partições"), "a mensagem precisa explicar a consequência")
        assertTrue(erro.message!!.contains("KAFKA_CONSUMER_GROUP_ID"), "e dizer o que fazer para corrigir")
    }

    @Test
    fun `permite subir com um group-id exclusivo do replay`() {
        assertNotNull(ReplayModeGuard(groupId = "balance-replay-20260902", productionGroupId = GRUPO_PRODUCAO))
    }
}
