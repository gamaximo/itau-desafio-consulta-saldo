package br.com.itau.challenge.balance.adapter.input.kafka

import br.com.itau.challenge.balance.adapter.input.kafka.dto.TransactionEventMessage
import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import br.com.itau.challenge.balance.domain.model.ProcessedTransaction
import br.com.itau.challenge.balance.domain.model.ProcessingOutcome
import br.com.itau.challenge.balance.port.input.ProcessTransactionUseCase
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

@Component
class TransactionEventConsumer(
    private val processTransactionUseCase: ProcessTransactionUseCase,
    private val objectMapper: ObjectMapper,
    private val metrics: TransactionProcessingMetrics,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${transactions.topic-name}"])
    fun consume(payload: String) {
        val event = parse(payload)
        val outcome = processTransactionUseCase.process(event)
        metrics.recordOutcome(outcome)

        // Saldos aplicados são o caminho quente e afogariam os logs em volume de produção, então
        // ficam em DEBUG. Os dois resultados não aplicados ficam em INFO: são raros o bastante
        // para serem viáveis e são exatamente o que um operador precisa quando um cliente jura
        // que o saldo dele está errado.
        when (outcome) {
            ProcessingOutcome.APPLIED ->
                logger.debug(
                    "Balance applied: account={} transaction={} version={}",
                    event.account.id,
                    event.transaction.id,
                    event.transaction.timestamp,
                )

            ProcessingOutcome.STALE_DISCARDED ->
                logger.info(
                    "Stale event discarded, stored balance is already newer: account={} transaction={} version={}",
                    event.account.id,
                    event.transaction.id,
                    event.transaction.timestamp,
                )

            ProcessingOutcome.DECLINED_SKIPPED ->
                logger.info(
                    "Declined transaction skipped by configuration: account={} transaction={}",
                    event.account.id,
                    event.transaction.id,
                )
        }
    }

    private fun parse(payload: String): ProcessedTransaction =
        try {
            objectMapper.readValue(payload, TransactionEventMessage::class.java).toDomain()
        } catch (exception: JacksonException) {
            // JSON sintaticamente quebrado. Relançado como exceção de domínio para que o error
            // handler veja um único tipo não retentável e mande a mensagem para o dead letter
            // topic, em vez de retentar um payload que não tem como ser interpretado na segunda
            // tentativa.
            metrics.recordRejected()
            throw InvalidTransactionEventException("Malformed transaction event payload: ${exception.message}")
        } catch (exception: InvalidTransactionEventException) {
            // JSON estruturalmente válido que quebra o contrato — campo ausente, enum
            // desconhecido, UUID malformado. Contabilizado aqui para que a métrica cubra os dois
            // caminhos de rejeição.
            metrics.recordRejected()
            throw exception
        }
}
