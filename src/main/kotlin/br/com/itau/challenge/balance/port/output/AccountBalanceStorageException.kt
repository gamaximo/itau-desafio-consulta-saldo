package br.com.itau.challenge.balance.port.output

/**
 * O armazenamento de saldos não pôde ser alcançado ou recusou a operação por um motivo de
 * infraestrutura — throttling, timeout, falha de conexão.
 *
 * Vive em `port` e não em `domain` porque indisponibilidade é propriedade do contrato entre o
 * núcleo e o mundo externo, não uma regra de negócio. É isso que permite ao adaptador traduzir
 * `SdkException` em algo que o resto da aplicação entende, sem que os casos de uso nem o
 * adaptador web importem qualquer tipo da AWS. Trocar o DynamoDB por outro armazenamento muda
 * qual exceção é capturada, e nenhuma linha do código que reage a ela.
 */
class AccountBalanceStorageException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)
