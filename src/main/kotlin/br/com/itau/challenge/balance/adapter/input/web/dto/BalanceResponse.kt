package br.com.itau.challenge.balance.adapter.input.web.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

/**
 * Contrato de resposta de `GET /balances/{accountId}`.
 *
 * Mantido separado do modelo de domínio para que uma renomeação no núcleo não quebre em silêncio
 * uma API publicada — o compilador obriga a atualizar o mapeamento.
 */
data class BalanceResponse(
    val id: String,
    val owner: String,
    val balance: BalanceAmountResponse,
    /**
     * ISO 8601 com offset, já formatado, em vez de um `Instant` cru entregue ao Jackson.
     * Serializar o timestamp aqui mantém o formato de saída fixado por esta classe e coberto pelo
     * teste dela, em vez de depender da configuração ambiente do serializador.
     */
    @get:JsonProperty("updated_at") val updatedAt: String,
)

data class BalanceAmountResponse(
    val amount: BigDecimal,
    val currency: String,
)
