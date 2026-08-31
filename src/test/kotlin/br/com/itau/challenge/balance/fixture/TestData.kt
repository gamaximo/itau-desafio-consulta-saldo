package br.com.itau.challenge.balance.fixture

import br.com.itau.challenge.balance.domain.model.Account
import br.com.itau.challenge.balance.domain.model.AccountBalance
import br.com.itau.challenge.balance.domain.model.AccountStatus
import br.com.itau.challenge.balance.domain.model.Money
import br.com.itau.challenge.balance.domain.model.ProcessedTransaction
import br.com.itau.challenge.balance.domain.model.Transaction
import br.com.itau.challenge.balance.domain.model.TransactionStatus
import br.com.itau.challenge.balance.domain.model.TransactionType
import java.math.BigDecimal

const val ACCOUNT_ID = "5b19c8b6-0cc4-4c72-a989-0c2ee15fa975"
const val OWNER_ID = "315e3cfe-f4af-4cd2-b298-a449e614349a"
const val TRANSACTION_ID = "8e8ae808-b154-48b5-9f3e-553935cc4543"

/** 2025-07-04T15:02:44.589998Z — the timestamp from the challenge's sample payload. */
const val TRANSACTION_TIMESTAMP = 1751641364589998L

const val ACCOUNT_CREATED_AT = 1634874339000000L

/**
 * Builders with sensible defaults, so each test overrides only the field it is actually about.
 * A test that says `transaction(timestamp = older)` reads as the scenario it describes, with no
 * ceremony around the six fields that are irrelevant to it.
 */

fun money(
    amount: String = "183.12",
    currency: String = "BRL",
) = Money(amount = BigDecimal(amount), currency = currency)

fun transaction(
    id: String = TRANSACTION_ID,
    type: TransactionType = TransactionType.CREDIT,
    amount: Money = money("97.07"),
    status: TransactionStatus = TransactionStatus.APPROVED,
    timestamp: Long = TRANSACTION_TIMESTAMP,
) = Transaction(id = id, type = type, amount = amount, status = status, timestamp = timestamp)

fun account(
    id: String = ACCOUNT_ID,
    owner: String = OWNER_ID,
    createdAt: Long = ACCOUNT_CREATED_AT,
    status: AccountStatus = AccountStatus.ENABLED,
    balance: Money = money(),
) = Account(id = id, owner = owner, createdAt = createdAt, status = status, balance = balance)

fun processedTransaction(
    transaction: Transaction = transaction(),
    account: Account = account(),
) = ProcessedTransaction(transaction = transaction, account = account)

fun accountBalance(
    accountId: String = ACCOUNT_ID,
    owner: String = OWNER_ID,
    balance: Money = money(),
    lastTransactionId: String = TRANSACTION_ID,
    version: Long = TRANSACTION_TIMESTAMP,
) = AccountBalance(
    accountId = accountId,
    owner = owner,
    balance = balance,
    lastTransactionId = lastTransactionId,
    version = version,
)
