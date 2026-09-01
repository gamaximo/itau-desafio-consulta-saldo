package br.com.itau.challenge.balance.adapter.input.web

import br.com.itau.challenge.balance.adapter.input.web.dto.BalanceResponse
import br.com.itau.challenge.balance.port.input.GetAccountBalanceUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.slf4j.MDC
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
     * Declarar a variável de path como [UUID], e não como [String], joga a validação de formato
     * para dentro do framework: um identificador malformado é rejeitado com 400 antes de qualquer
     * caso de uso rodar, e antes de uma única requisição chegar ao DynamoDB. Só identificadores
     * bem formados conseguem produzir um 404, o que impede que "malformado" e "não encontrado"
     * virem a mesma resposta.
     */
    @Operation(
        summary = "Consulta o saldo atual de uma conta",
        description =
            "Retorna o saldo da transação mais recente liquidada para esta conta, " +
                "conforme projetado a partir do tópico de transações.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Saldo atual da conta"),
        ApiResponse(responseCode = "400", description = "O identificador da conta não é um UUID válido"),
        ApiResponse(responseCode = "404", description = "Nenhum saldo foi projetado para esta conta"),
        ApiResponse(responseCode = "503", description = "O armazenamento de saldos está indisponível — retente"),
    )
    @GetMapping("/balances/{accountId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getBalance(
        @Parameter(description = "Identificador da conta", example = "5b19c8b6-0cc4-4c72-a989-0c2ee15fa975")
        @PathVariable accountId: UUID,
    ): BalanceResponse {
        val balance = getAccountBalanceUseCase.getBalance(accountId.toString())

        // Conta e titular vão para o MDC com **os mesmos nomes usados na ingestão**, e é essa
        // coincidência que dá o ganho: uma única consulta por `owner:…` passa a devolver tanto os
        // eventos consumidos quanto as chamadas à API daquele titular, em ordem cronológica.
        //
        // Preenchido aqui porque este é o primeiro ponto que conhece o titular — o filtro de
        // acesso só enxerga a URL, e a URL carrega apenas o identificador da conta. Como o filtro
        // registra a linha no `finally`, depois deste método retornar, os campos já estão postos
        // quando ele escreve. A limpeza também é dele, que é a fronteira da requisição.
        MDC.put("account", accountId.toString())
        MDC.put("owner", balance.owner)

        return balance.toResponse(zoneId)
    }
}
