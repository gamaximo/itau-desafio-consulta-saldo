package br.com.itau.challenge.balance.domain.model

/**
 * Por que uma escrita não foi aplicada.
 *
 * Distinguir os dois casos importa em operação, porque eles têm causas diferentes e alarmes
 * diferentes. [OUT_OF_ORDER] é o funcionamento normal de um tópico sem chave de partição: eventos
 * de uma conta se espalham e chegam fora de ordem. Já uma taxa alta e persistente de
 * [DUPLICATE] indica um produtor reenviando — ou um consumidor preso reprocessando o mesmo
 * offset.
 *
 * Agregados num contador único, os dois somem dentro do mesmo número e ninguém percebe a
 * diferença até investigar manualmente.
 */
enum class RejectionReason {
    /** A versão armazenada é exatamente igual: é o mesmo evento chegando de novo. */
    DUPLICATE,

    /** A versão armazenada é mais recente: este evento é antigo e perdeu a disputa. */
    OUT_OF_ORDER,
}
