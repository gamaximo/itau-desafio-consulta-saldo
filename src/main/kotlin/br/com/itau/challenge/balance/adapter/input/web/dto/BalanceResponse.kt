package br.com.itau.challenge.balance.adapter.input.web.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

/**
 * Response contract of `GET /balances/{accountId}`.
 *
 * Kept separate from the domain model so that a rename in the core cannot silently break a
 * published API — the compiler forces the mapping to be updated instead.
 */
data class BalanceResponse(
    val id: String,
    val owner: String,
    val balance: BalanceAmountResponse,
    /**
     * Pre-formatted ISO 8601 with offset, rather than a raw `Instant` left to Jackson.
     * Serializing the timestamp here keeps the wire format pinned by this class and covered by
     * its test, instead of depending on the ambient serializer configuration.
     */
    @get:JsonProperty("updated_at") val updatedAt: String,
)

data class BalanceAmountResponse(
    val amount: BigDecimal,
    val currency: String,
)
