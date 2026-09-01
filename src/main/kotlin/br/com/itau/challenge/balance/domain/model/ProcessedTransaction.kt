package br.com.itau.challenge.balance.domain.model

/**
 * Uma transação liquidada junto com o snapshot da conta que ela produziu — o evento completo
 * publicado em `transacoes-financeiras-processadas`.
 *
 * ### Por que este serviço projeta em vez de acumular
 *
 * O autorizador envia `account.balance` já liquidado. Recalcular o saldo aqui, somando créditos
 * e subtraindo débitos, seria estritamente pior: exigiria que cada evento chegasse exatamente
 * uma vez e em ordem — nada disso é garantido pelo Kafka entre partições — e qualquer lacuna
 * corromperia o saldo silenciosamente e para sempre. Projetar o snapshot autoritativo torna cada
 * evento autossuficiente, e é isso que permite resolver duplicatas e entregas fora de ordem
 * simplesmente comparando versões.
 */
data class ProcessedTransaction(
    val transaction: Transaction,
    val account: Account,
) {
    /**
     * Projeta este evento no estado de saldo a ser persistido.
     *
     * A versão é o timestamp da transação, o que mantém a projeção determinística: reprocessar o
     * tópico a partir do offset zero produz itens idênticos byte a byte, não importa quando o
     * replay aconteça.
     */
    fun toAccountBalance(): AccountBalance =
        AccountBalance(
            accountId = account.id,
            owner = account.owner,
            balance = account.balance,
            lastTransactionId = transaction.id,
            version = transaction.timestamp,
        )
}
