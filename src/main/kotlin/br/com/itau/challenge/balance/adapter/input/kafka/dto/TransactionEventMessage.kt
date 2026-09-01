package br.com.itau.challenge.balance.adapter.input.kafka.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

/**
 * Formato de uma mensagem no tópico `transacoes-financeiras-processadas`.
 *
 * Todos os campos são nulláveis mesmo que o contrato diga o contrário: isto é entrada não
 * confiável vinda de um tópico, e modelá-la como não-nula deixaria o Jackson lançar um erro de
 * desserialização lá no fundo do parser, em vez de permitir que o mapper informe *qual* campo
 * está faltando. É no mapeamento para o domínio que a ausência vira um erro preciso e passível
 * de ir para o dead letter topic.
 *
 * Propriedades desconhecidas são ignoradas para que um produtor que adicione um campo — mudança
 * rotineira e retrocompatível do lado dele — não consiga derrubar este consumidor.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TransactionEventMessage(
    val transaction: TransactionMessage? = null,
    val account: AccountMessage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TransactionMessage(
    val id: String? = null,
    val type: String? = null,
    // BigDecimal, não Double: caso contrário o Jackson vincularia 97.07 ao double binário mais
    // próximo e o decimal exato se perderia antes mesmo de o domínio ver o valor.
    val amount: BigDecimal? = null,
    val currency: String? = null,
    val status: String? = null,
    val timestamp: Long? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountMessage(
    val id: String? = null,
    val owner: String? = null,
    @param:JsonProperty("created_at") val createdAt: Long? = null,
    val status: String? = null,
    val balance: BalanceMessage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BalanceMessage(
    val amount: BigDecimal? = null,
    val currency: String? = null,
)
