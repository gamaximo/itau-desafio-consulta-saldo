package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/**
 * A monetary amount and its currency, normalised to the currency's own scale.
 *
 * ### Why the amount is a BigDecimal
 *
 * Binary floating point cannot represent decimal fractions like 0.01 exactly, so a balance
 * carried through a Double drifts. The value stays an exact decimal across the whole pipeline:
 * Kafka payload, domain, DynamoDB attribute and HTTP response.
 *
 * ### Why the scale is normalised
 *
 * DynamoDB trims trailing zeros from Number attributes — write `300.00` and you read back
 * `300`. Left alone, the same balance would be reported as `300.00` right after ingestion and
 * as `300` after a round trip through storage, and `BigDecimal.equals` would call those two
 * different values. Pinning the scale to the currency's own fraction digits makes the amount
 * stable end to end: an account in BRL always reports two decimal places, whatever the store
 * did to it in between.
 *
 * Note this is deliberately *not* a plain `equals` fix. Comparing with `compareTo` would make
 * the tests pass while still letting the API answer `300` for a balance in reais.
 */
class Money(
    amount: BigDecimal,
    val currency: String,
) {
    val amount: BigDecimal

    init {
        val isoCurrency =
            try {
                Currency.getInstance(currency)
            } catch (_: IllegalArgumentException) {
                throw InvalidTransactionEventException("Currency must be an ISO 4217 code, got '$currency'")
            } catch (_: NullPointerException) {
                throw InvalidTransactionEventException("Currency must be an ISO 4217 code, got '$currency'")
            }

        // Fraction digits come from the currency itself rather than a hardcoded 2, because the
        // correct scale is a property of the money: JPY has none, BRL has two, BHD has three.
        // Pseudo-currencies such as XAU report -1, and those keep whatever scale they arrived
        // with.
        val fractionDigits = isoCurrency.defaultFractionDigits

        this.amount =
            if (fractionDigits < 0) {
                amount
            } else {
                try {
                    // UNNECESSARY, never HALF_UP: an amount carrying more precision than its
                    // currency allows — 1.234 in BRL — means the producer sent something this
                    // service does not understand. Rounding it away silently would invent a
                    // balance; rejecting it sends the event to the dead letter topic where a
                    // human can look at it.
                    amount.setScale(fractionDigits, RoundingMode.UNNECESSARY)
                } catch (_: ArithmeticException) {
                    throw InvalidTransactionEventException(
                        "Amount $amount carries more precision than $currency allows ($fractionDigits decimal places)",
                    )
                }
            }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Money && amount == other.amount && currency == other.currency)

    override fun hashCode(): Int = 31 * amount.hashCode() + currency.hashCode()

    override fun toString(): String = "Money(amount=$amount, currency='$currency')"
}
