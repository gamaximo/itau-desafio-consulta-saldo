package br.com.itau.challenge.balance.application

import br.com.itau.challenge.balance.domain.model.ProcessedTransaction
import br.com.itau.challenge.balance.domain.model.ProcessingOutcome
import br.com.itau.challenge.balance.domain.model.TransactionStatus
import br.com.itau.challenge.balance.port.input.ProcessTransactionUseCase
import br.com.itau.challenge.balance.port.output.AccountBalanceRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class ProcessTransactionService(
    private val accountBalanceRepository: AccountBalanceRepository,
    // --- balance.apply-declined-transactions -------------------------------------------------
    // Uma transação DECLINED deve atualizar o saldo armazenado?
    //
    // É genuinamente ambíguo, por isso virou flag em vez de regra fixa no código.
    //
    // O padrão é `true`, porque uma transação recusada continua carregando um snapshot de saldo
    // válido: o autorizador avaliou a conta naquele microssegundo e informou qual era o saldo.
    // Recusar um débito por saldo insuficiente não move dinheiro, mas também não torna o saldo
    // informado incorreto. Descartar esses eventos significaria ignorar a leitura mais recente
    // que o sistema tem — e, numa conta cujas transações são majoritariamente recusadas, o saldo
    // armazenado envelheceria sem um bom motivo.
    //
    // Configure como `false` para projetar apenas transações aprovadas. Essa é a leitura mais
    // conservadora — "o saldo muda quando o dinheiro se move" — e é o ajuste correto caso algum
    // dia se constate que o autorizador emite o saldo pré-autorização nas recusas, em vez do
    // saldo liquidado. Com `false`, a versão armazenada passa a ser a do último APPROVED, então
    // um APPROVED posterior continua sendo aplicado normalmente; a corretude se mantém nos dois
    // modos, muda apenas o frescor.
    // -----------------------------------------------------------------------------------------
    @Value("\${balance.apply-declined-transactions}") private val applyDeclinedTransactions: Boolean,
) : ProcessTransactionUseCase {

    override fun process(processedTransaction: ProcessedTransaction): ProcessingOutcome {
        if (!applyDeclinedTransactions && processedTransaction.transaction.status == TransactionStatus.DECLINED) {
            return ProcessingOutcome.DECLINED_SKIPPED
        }

        val applied = accountBalanceRepository.saveIfNewer(processedTransaction.toAccountBalance())

        return if (applied) ProcessingOutcome.APPLIED else ProcessingOutcome.STALE_DISCARDED
    }
}
