package br.com.itau.challenge.balance.adapter.input.web

import br.com.itau.challenge.balance.adapter.input.web.dto.BalanceAmountResponse
import br.com.itau.challenge.balance.adapter.input.web.dto.BalanceResponse
import br.com.itau.challenge.balance.domain.model.AccountBalance
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * ISO 8601 de largura fixa, com seis dígitos de fração e offset explícito.
 *
 * O `DateTimeFormatter.ISO_OFFSET_DATE_TIME` imprime o número *mínimo* de dígitos fracionários,
 * então o mesmo campo voltaria como `.4848` para um saldo e `.589998` para outro — e como `.0`,
 * ou nada, num segundo cheio. Um consumidor que espera um layout fixo quebraria justamente no
 * caso que ele não encontrou primeiro.
 *
 * Seis dígitos porque os timestamps de origem são microssegundos: qualquer coisa menor truncaria
 * silenciosamente a precisão que o autorizador realmente enviou.
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
