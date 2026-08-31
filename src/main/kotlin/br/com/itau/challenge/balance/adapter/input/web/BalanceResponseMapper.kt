package br.com.itau.challenge.balance.adapter.input.web

import br.com.itau.challenge.balance.adapter.input.web.dto.BalanceAmountResponse
import br.com.itau.challenge.balance.adapter.input.web.dto.BalanceResponse
import br.com.itau.challenge.balance.domain.model.AccountBalance
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Fixed-width ISO 8601 with a six-digit fraction and an explicit offset.
 *
 * `DateTimeFormatter.ISO_OFFSET_DATE_TIME` prints the *minimum* number of fractional digits, so
 * the same field would come back as `.4848` for one balance and `.589998` for another, and
 * `.0` — or nothing at all — on a whole second. A consumer parsing a fixed layout would break
 * on whichever case it happened not to see first.
 *
 * Six digits because the source timestamps are microseconds: anything shorter would silently
 * truncate the precision the authorizer actually sent.
 */
private val ISO_8601_MICROS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX")

internal fun AccountBalance.toResponse(zoneId: ZoneId): BalanceResponse =
    BalanceResponse(
        id = accountId,
        owner = owner,
        balance =
            BalanceAmountResponse(
                amount = balance.amount,
                currency = balance.currency,
            ),
        updatedAt = ISO_8601_MICROS.format(updatedAt.atZone(zoneId)),
    )
