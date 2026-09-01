package br.com.itau.challenge.balance.domain.model

/**
 * O que o pipeline fez com um evento válido. Todo valor aqui é um **sucesso** — nenhum deles é
 * erro que valha retentar — mas são contados separadamente para que operadores consigam
 * distinguir um fluxo saudável de um quebrado.
 *
 * Uma taxa crescente de [STALE_DISCARDED], por exemplo, é normal durante rebalanceamento de
 * partições, mas suspeita se persistir: sugeriria um produtor reenviando offsets antigos.
 */
enum class ProcessingOutcome {
    /** O snapshot era mais recente que o armazenado e passou a ser o saldo atual. */
    APPLIED,

    /**
     * O saldo armazenado já estava na mesma versão ou à frente deste evento, então a escrita foi
     * rejeitada pela condição. Cobre tanto entrega fora de ordem quanto duplicatas.
     */
    STALE_DISCARDED,

    /**
     * A transação foi recusada e o serviço está configurado para não projetar transações
     * recusadas. Veja `balance.apply-declined-transactions`.
     */
    DECLINED_SKIPPED,
}
