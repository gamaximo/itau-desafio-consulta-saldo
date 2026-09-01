package br.com.itau.challenge.balance.port.input

import br.com.itau.challenge.balance.domain.model.ProcessedTransaction
import br.com.itau.challenge.balance.domain.model.ProcessingOutcome

fun interface ProcessTransactionUseCase {
    fun process(processedTransaction: ProcessedTransaction): ProcessingOutcome
}
