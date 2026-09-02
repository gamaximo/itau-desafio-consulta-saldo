package br.com.itau.challenge.balance.adapter.output.dynamodb

import br.com.itau.challenge.balance.domain.exception.InvalidTransactionEventException
import br.com.itau.challenge.balance.port.output.AccountBalanceStorageException
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.exception.SdkServiceException
import software.amazon.awssdk.services.dynamodb.model.InternalServerErrorException
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException

/**
 * Traduz uma falha do SDK na exceção certa — e a escolha decide por quanto tempo a partição fica
 * ocupada.
 *
 * Nem todo erro do DynamoDB tem a mesma natureza:
 *
 *  - **Problema do serviço** — throttling, `5xx`, timeout, conexão caída. A próxima tentativa tem
 *    chance real de funcionar, então vira [AccountBalanceStorageException] e recebe o retry longo.
 *  - **Problema da requisição** — `4xx` como `ValidationException`: item acima do limite de
 *    tamanho, atributo com tipo inválido. A **mesma** requisição vai falhar exatamente igual daqui
 *    a 30 minutos, porque o defeito está no que enviamos, não em quem recebe.
 *
 * Tratar os dois igual significa ocupar a partição por meia hora retentando algo que nunca vai
 * funcionar, enquanto as mensagens boas atrás esperam. Por isso o `4xx` vira
 * [InvalidTransactionEventException] e vai direto ao dead letter topic, onde fica visível.
 *
 * Foi assim que o *number overflow* se comportava antes de ser barrado no domínio: um erro
 * permanente disfarçado de indisponibilidade, consumindo tentativas até desistir.
 */
internal fun SdkException.toStorageFailure(mensagem: String): RuntimeException =
    if (isPermanentRequestError()) {
        InvalidTransactionEventException("$mensagem — o armazenamento rejeitou a requisição: ${this.message}")
    } else {
        AccountBalanceStorageException(mensagem, this)
    }

private fun SdkException.isPermanentRequestError(): Boolean {
    // Falha de cliente sem resposta do serviço — rede, timeout, DNS. Não há status a interpretar,
    // e é justamente o caso mais transitório de todos.
    if (this !is SdkServiceException) return false

    // Throttling identificado pelo **tipo**, antes de qualquer heurística. O
    // `isThrottlingException()` do SDK depende do `errorCode` vir preenchido na resposta, e um
    // throttling classificado como permanente seria o pior engano possível: mandaria ao dead
    // letter topic justamente o evento que só precisava de mais uma tentativa.
    if (this is ProvisionedThroughputExceededException) return false
    if (this is RequestLimitExceededException) return false
    if (this is InternalServerErrorException) return false

    // As heurísticas do SDK entram depois, como rede de segurança para os códigos que a AWS
    // acrescentar sem que este código saiba.
    if (isThrottlingException || isRetryableException || isClockSkewException) return false

    // Tabela ausente é 4xx, mas é infraestrutura: pode ser recriada, e aí o retry resolve sozinho.
    // Mandar ao dead letter topic exigiria reprocessamento manual de algo que se conserta sozinho.
    if (this is ResourceNotFoundException) return false

    return statusCode() in 400..499
}
