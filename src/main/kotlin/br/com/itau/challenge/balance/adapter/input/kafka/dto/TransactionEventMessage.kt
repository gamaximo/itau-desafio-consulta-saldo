package br.com.itau.challenge.balance.adapter.input.kafka.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

/**
 * Wire shape of a message on `transacoes-financeiras-processadas`.
 *
 * Every field is nullable even though the contract says otherwise: this is untrusted input off
 * a topic, and modelling it as non-null would let Jackson throw a deserialization error deep
 * in the parser instead of letting the mapper report *which* field is missing. The mapping to
 * the domain is where absence becomes a precise, dead-letterable error.
 *
 * Unknown properties are ignored so that a producer adding a field — a routine, backward
 * compatible change upstream — cannot take this consumer down.
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
    // BigDecimal, not Double: Jackson would otherwise bind 97.07 to the nearest binary double
    // and the exact decimal would be lost before the domain ever sees it.
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
