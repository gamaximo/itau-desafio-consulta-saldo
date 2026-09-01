package br.com.itau.challenge.balance.adapter.input.kafka

import br.com.itau.challenge.balance.adapter.input.kafka.dto.TransactionEventMessage
import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import br.com.itau.challenge.balance.domain.model.ProcessedTransaction
import br.com.itau.challenge.balance.domain.model.ProcessingOutcome
import br.com.itau.challenge.balance.port.input.ProcessTransactionUseCase
import org.slf4j.LoggerFactory
import org.slf4j.MDC
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

        // Os identificadores vão para o MDC, e não apenas interpolados na mensagem, para virarem
        // campos pesquisáveis no agregador: `account:ad94db9f-…` é uma consulta; procurar o mesmo
        // UUID dentro de texto livre depende de regex e de a mensagem nunca mudar de formato.
        //
        // `owner` entra junto porque investigações raramente começam pela conta: quem abre um
        // chamado é o titular, e sem este campo seria preciso primeiro descobrir quais contas são
        // dele para só então filtrar o log. É também a razão de não existir um GSI por `owner` —
        // ver a decisão 2 do README: resolver isto no log custa um campo, e resolver no banco
        // custaria escrita em toda ingestão.
        MDC.put("account", event.account.id)
        MDC.put("owner", event.account.owner)
        MDC.put("transaction", event.transaction.id)
        MDC.put("version", event.transaction.timestamp.toString())

        try {
            registerOutcome(event)
        } finally {
            // Threads do container são reaproveitadas entre mensagens: sem limpar, o contexto de
            // uma vazaria para a próxima e o log atribuiria o evento à conta errada.
            listOf("account", "owner", "transaction", "version").forEach(MDC::remove)
        }
    }

    private fun registerOutcome(event: ProcessedTransaction) {
        val outcome = processTransactionUseCase.process(event)
        metrics.recordOutcome(outcome)

        // Conta, transação e versão já estão no MDC, então as mensagens ficam só com o que
        // aconteceu — sem repetir os identificadores dentro do texto.
        //
        // Saldos aplicados são o caminho quente e afogariam os logs em volume de produção, então
        // ficam em DEBUG. O rastro definitivo do que foi aplicado não depende de log: está no
        // próprio item persistido, em `lastTransactionId` e `version`, e no evento retido pelo
        // Kafka. Para investigar um caso específico, basta subir o nível deste pacote — sem
        // deploy, via `logging.level.br.com.itau.challenge.balance.adapter.input.kafka=DEBUG`.
        //
        // Os dois resultados não aplicados ficam em INFO: são raros o bastante para serem
        // viáveis e são exatamente o que um operador precisa quando um cliente contesta o saldo.
        when (outcome) {
            ProcessingOutcome.APPLIED -> logger.debug("Saldo aplicado")

            ProcessingOutcome.STALE_DISCARDED ->
                logger.info("Evento obsoleto descartado: o saldo armazenado já é mais recente")

            ProcessingOutcome.DECLINED_SKIPPED ->
                logger.info("Transação recusada ignorada por configuração")
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
            throw InvalidTransactionEventException("Payload de evento de transação malformado: ${exception.message}")
        } catch (exception: InvalidTransactionEventException) {
            // JSON estruturalmente válido que quebra o contrato — campo ausente, enum
            // desconhecido, UUID malformado. Contabilizado aqui para que a métrica cubra os dois
            // caminhos de rejeição.
            metrics.recordRejected()
            throw exception
        }
}
