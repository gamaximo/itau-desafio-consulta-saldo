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
    // Should a DECLINED transaction update the stored balance?
    //
    // It is genuinely ambiguous, so it is a flag rather than a hardcoded rule.
    //
    // Default `true`, because a declined transaction still carries a valid balance snapshot:
    // the authorizer evaluated the account at that microsecond and reported what the balance
    // was. Refusing an insufficient-funds debit does not move money, but it does not make the
    // reported balance wrong either. Dropping those events would mean ignoring the freshest
    // reading the system has — and for an account whose transactions are mostly declined, the
    // stored balance would go stale for no good reason.
    //
    // Set to `false` to project approved transactions only. That is the more conservative
    // reading — "balance changes only when money moves" — and is the right setting if the
    // upstream authorizer is ever found to emit a pre-authorization balance on declines
    // instead of the settled one. Under `false` the version stored is the last APPROVED
    // timestamp, so a later APPROVED event still applies cleanly; correctness is preserved
    // either way, only freshness differs.
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
