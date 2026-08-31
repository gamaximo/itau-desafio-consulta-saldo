package br.com.itau.challenge.balance.adapter.input.web

import br.com.itau.challenge.balance.adapter.input.web.dto.BalanceResponse
import br.com.itau.challenge.balance.port.input.GetAccountBalanceUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneId
import java.util.UUID

@RestController
class BalanceController(
    private val getAccountBalanceUseCase: GetAccountBalanceUseCase,
    @Value("\${api.time-zone}") timeZone: String,
) {
    private val zoneId = ZoneId.of(timeZone)

    /**
     * Declaring the path variable as [UUID] rather than [String] pushes format validation into
     * the framework: a malformed identifier is rejected with 400 before any use case runs, and
     * before a single request reaches DynamoDB. Only well-formed identifiers can ever produce
     * a 404, which keeps "malformed" and "not found" from collapsing into the same answer.
     */
    @Operation(
        summary = "Get the current balance of an account",
        description =
            "Returns the balance from the most recent transaction settled for this account, " +
                "as projected from the transactions topic.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Current balance of the account"),
        ApiResponse(responseCode = "400", description = "The account identifier is not a valid UUID"),
        ApiResponse(responseCode = "404", description = "No balance has been projected for this account"),
        ApiResponse(responseCode = "503", description = "The balance store is unavailable — retry"),
    )
    @GetMapping("/balances/{accountId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getBalance(
        @Parameter(description = "Account identifier", example = "5b19c8b6-0cc4-4c72-a989-0c2ee15fa975")
        @PathVariable accountId: UUID,
    ): BalanceResponse = getAccountBalanceUseCase.getBalance(accountId.toString()).toResponse(zoneId)
}
