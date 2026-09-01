package br.com.itau.challenge.balance.application

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import br.com.itau.challenge.balance.domain.model.ProcessedTransaction
import br.com.itau.challenge.balance.domain.model.ProcessingOutcome
import br.com.itau.challenge.balance.domain.model.RejectionReason
import br.com.itau.challenge.balance.domain.model.TransactionStatus
import br.com.itau.challenge.balance.domain.model.microsToInstant
import br.com.itau.challenge.balance.port.input.ProcessTransactionUseCase
import br.com.itau.challenge.balance.port.output.AccountBalanceRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration

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
    // informado incorreto.
    //
    // Configure como `false` para projetar apenas transações aprovadas — a leitura mais
    // conservadora, e o ajuste correto caso se constate que o autorizador emite o saldo
    // pré-autorização nas recusas.
    // -----------------------------------------------------------------------------------------
    @Value("\${balance.apply-declined-transactions}") private val applyDeclinedTransactions: Boolean,
    // --- balance.max-clock-skew ----------------------------------------------------------------
    // Quão adiantado um evento pode estar em relação ao nosso relógio antes de ser rejeitado.
    //
    // Existe porque a ordenação deste serviço confia num timestamp produzido por outro sistema, e
    // essa confiança tem um custo: um evento com timestamp absurdamente no futuro venceria todos
    // os seguintes e **congelaria a conta** — o saldo pararia de atualizar em silêncio, sem erro
    // em lugar nenhum, até a data chegar. Basta um produtor trocar microssegundos por
    // nanossegundos, ou ter o relógio adiantado, para produzir isso.
    //
    // A tolerância cobre a diferença de relógio legítima entre máquinas; acima dela, o evento vai
    // para o dead letter topic, onde fica visível e investigável em vez de envenenar a conta.
    // -------------------------------------------------------------------------------------------
    @Value("\${balance.max-clock-skew}") private val maxClockSkew: Duration,
    private val clock: Clock,
) : ProcessTransactionUseCase {

    override fun process(processedTransaction: ProcessedTransaction): ProcessingOutcome {
        rejectIfFromTheFuture(processedTransaction)

        if (!applyDeclinedTransactions && processedTransaction.transaction.status == TransactionStatus.DECLINED) {
            return ProcessingOutcome.DECLINED_SKIPPED
        }

        return when (accountBalanceRepository.saveIfNewer(processedTransaction.toAccountBalance())) {
            null -> ProcessingOutcome.APPLIED
            RejectionReason.DUPLICATE -> ProcessingOutcome.DUPLICATE_DISCARDED
            RejectionReason.OUT_OF_ORDER -> ProcessingOutcome.OUT_OF_ORDER_DISCARDED
        }
    }

    private fun rejectIfFromTheFuture(processedTransaction: ProcessedTransaction) {
        val eventInstant = microsToInstant(processedTransaction.transaction.timestamp)
        val limit = clock.instant().plus(maxClockSkew)

        if (eventInstant.isAfter(limit)) {
            throw InvalidTransactionEventException(
                "transaction.timestamp está ${maxClockSkew.toMinutes()} minutos ou mais no futuro " +
                    "($eventInstant); o valor pode estar em outra unidade que não microssegundos, " +
                    "ou o relógio do produtor está adiantado",
            )
        }
    }
}
