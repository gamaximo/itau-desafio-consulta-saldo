package br.com.itau.challenge.balance.adapter.input.kafka

import br.com.itau.challenge.balance.adapter.input.kafka.dto.TransactionEventMessage
import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import br.com.itau.challenge.balance.domain.model.ProcessedTransaction
import br.com.itau.challenge.balance.domain.model.ProcessingOutcome
import br.com.itau.challenge.balance.port.input.ProcessTransactionUseCase
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

private val CONTEXT_KEYS = listOf("account", "owner", "transaction", "version")

@Component
class TransactionEventConsumer(
    private val processTransactionUseCase: ProcessTransactionUseCase,
    private val objectMapper: ObjectMapper,
    private val metrics: TransactionProcessingMetrics,
    @Value("\${spring.kafka.consumer.group-id}") private val consumerGroup: String,
    @Value("\${balance.replay.enabled}") private val replayMode: Boolean,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${transactions.topic-name}"])
    fun consume(payload: String) {
        // O contexto é preenchido **antes** de validar, com o que der para extrair do payload cru.
        // A ordem importa: se a validação viesse primeiro, a linha de log mais importante — a do
        // dead letter topic — seria justamente a única sem conta e sem titular, e a investigação
        // começaria sem saber de quem é o evento que quebrou.
        //
        // Os identificadores vão para o MDC, e não interpolados na mensagem, para virarem campos
        // pesquisáveis: `account:ad94db9f-…` é uma consulta. `owner` entra junto porque
        // investigações raramente começam pela conta — quem abre um chamado é o titular.
        // Durante um reprocessamento existem duas instâncias consumindo o mesmo tópico ao mesmo
        // tempo, e sem estes campos as linhas das duas se misturam no agregador — milhares de
        // "evento duplicado descartado" sem indicação de quem os produziu. `consumerGroup` é o
        // fato; `replay` é a interpretação, e existe para a consulta não depender da convenção de
        // nome do grupo.
        MDC.put("consumerGroup", consumerGroup)
        MDC.put("replay", replayMode.toString())

        putBestEffortContext(payload)

        try {
            process(payload)
        } catch (exception: InvalidTransactionEventException) {
            // Toda rejeição passa por aqui, venha da desserialização, do contrato ou de uma regra
            // do caso de uso — como o timestamp implausível. Um ponto único de contagem evita que
            // uma validação criada amanhã fique fora da métrica sem ninguém notar.
            metrics.recordRejected()
            throw exception
        } finally {
            // Threads do container são reaproveitadas entre mensagens: sem limpar, o contexto de
            // uma vazaria para a próxima e o log atribuiria o evento à conta errada.
            (CONTEXT_KEYS + listOf("consumerGroup", "replay")).forEach(MDC::remove)
        }
    }

    private fun process(payload: String) {
        val outcome = processTransactionUseCase.process(parse(payload))
        metrics.recordOutcome(outcome)

        // Os identificadores já estão no MDC, então as mensagens ficam só com o que aconteceu.
        //
        // Saldos aplicados são o caminho quente e afogariam os logs em volume de produção, então
        // ficam em DEBUG. O rastro definitivo não depende de log: está no item persistido, em
        // `lastTransactionId` e `version`, e no evento retido pelo Kafka. Para investigar um caso,
        // basta subir o nível deste pacote — sem deploy.
        when (outcome) {
            ProcessingOutcome.APPLIED -> logger.debug("Saldo aplicado")

            ProcessingOutcome.DUPLICATE_DISCARDED ->
                logger.info("Evento duplicado descartado: este mesmo evento já havia sido aplicado")

            ProcessingOutcome.OUT_OF_ORDER_DISCARDED ->
                logger.info("Evento fora de ordem descartado: o saldo armazenado já é mais recente")

            ProcessingOutcome.DECLINED_SKIPPED ->
                logger.info("Transação recusada ignorada por configuração")
        }
    }

    /**
     * Lê os identificadores direto da árvore JSON, sem validar nada.
     *
     * Best-effort de propósito: um payload que quebrou o contrato pode ainda assim carregar uma
     * conta perfeitamente legível — o caso típico é um `type` desconhecido num evento com todo o
     * resto correto. Se nem isso funcionar, segue sem contexto: registrar o erro importa mais que
     * os campos que o acompanham.
     */
    private fun putBestEffortContext(payload: String) {
        runCatching {
            val root = objectMapper.readTree(payload)
            val account = root.get("account")
            val transaction = root.get("transaction")

            account?.get("id")?.asString()?.let { MDC.put("account", it) }
            account?.get("owner")?.asString()?.let { MDC.put("owner", it) }
            transaction?.get("id")?.asString()?.let { MDC.put("transaction", it) }
            transaction?.get("timestamp")?.asString()?.let { MDC.put("version", it) }
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
            throw InvalidTransactionEventException("Payload de evento de transação malformado: ${exception.message}")
        }
}
