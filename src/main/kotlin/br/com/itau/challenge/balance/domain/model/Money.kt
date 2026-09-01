package br.com.itau.challenge.balance.domain.model

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/**
 * Um valor monetário e sua moeda, normalizado para a escala da própria moeda.
 *
 * ### Por que o montante é BigDecimal
 *
 * Ponto flutuante binário não representa frações decimais como 0.01 de forma exata, então um
 * saldo carregado por Double desvia. O valor permanece um decimal exato por todo o pipeline:
 * payload do Kafka, domínio, atributo do DynamoDB e resposta HTTP.
 *
 * ### Por que a escala é normalizada
 *
 * O DynamoDB remove zeros à direita de atributos numéricos — grave `300.00` e você lê de volta
 * `300`. Sem tratamento, o mesmo saldo seria reportado como `300.00` logo após a ingestão e
 * como `300` depois de passar pelo banco, e `BigDecimal.equals` consideraria os dois valores
 * diferentes. Fixar a escala nos dígitos fracionários da moeda torna o montante estável de ponta
 * a ponta: uma conta em BRL sempre reporta duas casas decimais, aconteça o que acontecer no
 * armazenamento.
 *
 * Repare que isto deliberadamente **não** é um conserto no `equals`. Comparar com `compareTo`
 * faria os testes passarem e ainda assim deixaria a API responder `300` para um saldo em reais.
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
                throw InvalidTransactionEventException("A moeda precisa ser um código ISO 4217, recebido '$currency'")
            } catch (_: NullPointerException) {
                throw InvalidTransactionEventException("Currency must be an ISO 4217 code, got '$currency'")
            }

        // Os dígitos fracionários vêm da própria moeda, e não de um 2 fixo no código, porque a
        // escala correta é uma propriedade do dinheiro: JPY não tem casas decimais, BRL tem
        // duas, BHD tem três. Pseudo-moedas como XAU reportam -1, e essas mantêm a escala com
        // que chegaram.
        val fractionDigits = isoCurrency.defaultFractionDigits

        this.amount =
            if (fractionDigits < 0) {
                amount
            } else {
                try {
                    // UNNECESSARY, nunca HALF_UP: um montante com mais precisão do que a moeda
                    // permite — 1.234 em BRL — significa que o produtor mandou algo que este
                    // serviço não entende. Arredondar em silêncio inventaria um saldo; rejeitar
                    // manda o evento para o dead letter topic, onde uma pessoa pode analisar.
                    amount.setScale(fractionDigits, RoundingMode.UNNECESSARY)
                } catch (_: ArithmeticException) {
                    throw InvalidTransactionEventException(
                        "O valor $amount tem mais precisão do que $currency permite ($fractionDigits casas decimais)",
                    )
                }
            }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Money && amount == other.amount && currency == other.currency)

    override fun hashCode(): Int = 31 * amount.hashCode() + currency.hashCode()

    override fun toString(): String = "Money(amount=$amount, currency='$currency')"
}
