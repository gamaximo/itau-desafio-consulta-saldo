package br.com.itau.challenge.balance.adapter.input.kafka

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Protege o grupo de produção quando a aplicação sobe em modo de reprocessamento.
 *
 * Reprocessar o tópico é feito subindo uma instância a mais com outro `group.id`, enquanto a de
 * produção segue atendendo. O risco mora justamente aí: se alguém esquecer de trocar o `group.id`,
 * a instância de replay **entra no grupo de produção**, dispara um rebalanceamento e passa a
 * disputar as partições com quem está servindo — transformando uma operação rotineira num
 * incidente.
 *
 * O erro é silencioso: nada falha, o consumo continua, e só o lag e a latência denunciariam.
 *
 * Por isso a aplicação se recusa a subir nessa combinação. Falhar na inicialização é barato;
 * descobrir depois, no meio do reprocessamento, não é.
 */
@Component
@ConditionalOnProperty("balance.replay.enabled", havingValue = "true")
class ReplayModeGuard(
    @Value("\${spring.kafka.consumer.group-id}") private val groupId: String,
    @Value("\${balance.replay.production-group-id}") private val productionGroupId: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        require(groupId != productionGroupId) {
            "Modo de reprocessamento ligado com o group-id de produção ('$groupId'). Esta instância " +
                "entraria no grupo que está servindo e disputaria as partições com ele. Defina " +
                "KAFKA_CONSUMER_GROUP_ID com um valor exclusivo para o replay."
        }

        logger.warn(
            "MODO DE REPROCESSAMENTO ATIVO — group-id '{}', lendo o tópico desde o início. " +
                "Esta instância não afeta o grupo de produção '{}'; eventos já aplicados serão " +
                "descartados como duplicata.",
            groupId,
            productionGroupId,
        )
    }
}
