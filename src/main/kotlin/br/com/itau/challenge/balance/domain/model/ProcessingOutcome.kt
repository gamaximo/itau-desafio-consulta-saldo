package br.com.itau.challenge.balance.domain.model

/**
 * O que o pipeline fez com um evento válido. Todo valor aqui é um **sucesso** — nenhum deles é
 * erro que valha retentar — mas são contados separadamente para que operadores consigam
 * distinguir um fluxo saudável de um quebrado.
 */
enum class ProcessingOutcome {
    /** O snapshot era mais recente que o armazenado e passou a ser o saldo atual. */
    APPLIED,

    /**
     * O evento é uma repetição exata de um já aplicado. Normal sob at-least-once; suspeito se a
     * taxa subir e não cair, o que sugeriria um produtor reenviando.
     */
    DUPLICATE_DISCARDED,

    /**
     * O evento é mais antigo que o saldo armazenado. É o funcionamento esperado de um tópico sem
     * chave de partição, onde eventos de uma mesma conta chegam em ordem arbitrária.
     */
    OUT_OF_ORDER_DISCARDED,

    /**
     * A transação foi recusada e o serviço está configurado para não projetar transações
     * recusadas. Veja `balance.apply-declined-transactions`.
     */
    DECLINED_SKIPPED,
}
