package br.com.itau.challenge.balance.adapter.input.kafka

import br.com.itau.challenge.balance.adapter.input.kafka.dto.TransactionEventMessage
import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import br.com.itau.challenge.balance.domain.model.Account
import br.com.itau.challenge.balance.domain.model.AccountStatus
import br.com.itau.challenge.balance.domain.model.Money
import br.com.itau.challenge.balance.domain.model.ProcessedTransaction
import br.com.itau.challenge.balance.domain.model.Transaction
import br.com.itau.challenge.balance.domain.model.TransactionStatus
import br.com.itau.challenge.balance.domain.model.TransactionType

/**
 * Traduz a mensagem que vem do tópico para o modelo de domínio, transformando cada campo ausente
 * numa [InvalidTransactionEventException] que nomeia o campo. A mensagem é colocada em quarentena
 * já na borda: nada depois deste ponto precisa lidar com nulos.
 */
internal fun TransactionEventMessage.toDomain(): ProcessedTransaction {
    val transactionMessage = transaction.required("transaction")
    val accountMessage = account.required("account")
    val balanceMessage = accountMessage.balance.required("account.balance")

    return ProcessedTransaction(
        transaction =
            Transaction(
                id = transactionMessage.id.required("transaction.id"),
                type = TransactionType.from(transactionMessage.type.required("transaction.type")),
                amount =
                    Money(
                        amount = transactionMessage.amount.required("transaction.amount"),
                        currency = transactionMessage.currency.required("transaction.currency"),
                    ),
                status = TransactionStatus.from(transactionMessage.status.required("transaction.status")),
                timestamp = transactionMessage.timestamp.required("transaction.timestamp"),
            ),
        account =
            Account(
                id = accountMessage.id.required("account.id"),
                owner = accountMessage.owner.required("account.owner"),
                createdAt = accountMessage.createdAt.required("account.created_at"),
                status = AccountStatus.from(accountMessage.status.required("account.status")),
                balance =
                    Money(
                        amount = balanceMessage.amount.required("account.balance.amount"),
                        currency = balanceMessage.currency.required("account.balance.currency"),
                    ),
            ),
    )
}

private fun <T : Any> T?.required(field: String): T =
    this ?: throw InvalidTransactionEventException("$field é obrigatório")
