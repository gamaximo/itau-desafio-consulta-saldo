package br.com.itau.challenge.balance.adapter.input.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifica a composição das probes, não o estado delas.
 *
 * O `application.yaml` afirma que "um contêiner que perdeu o DynamoDB deve parar de receber
 * tráfego sem ser morto e reiniciado". Isso só é verdade se o indicador do banco estiver
 * **dentro** do grupo de readiness e **fora** do de liveness — e essa é uma configuração fácil de
 * quebrar sem ninguém notar, porque tudo continua respondendo `UP` no dia a dia.
 *
 * O status em si é ignorado de propósito: aqui roda com ou sem DynamoDB no ar, e o que precisa
 * ficar fixado é a composição.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HealthProbesTest(
    @Autowired private val mockMvc: MockMvc,
) {
    private fun corpoDe(probe: String): String =
        mockMvc.get("/actuator/health/$probe").andReturn().response.contentAsString

    @Test
    fun `a readiness leva em conta a disponibilidade do dynamodb`() {
        assertTrue(
            corpoDe("readiness").contains("dynamoDb"),
            "sem o dynamoDb na readiness, a aplicação se declara pronta com o banco fora",
        )
    }

    /**
     * Se a liveness caísse junto com o banco, o orquestrador entraria em ciclo de reinício durante
     * uma indisponibilidade do DynamoDB — trocando uma dependência degradada por instâncias frias
     * igualmente degradadas, e ainda por cima em looping.
     */
    @Test
    fun `a liveness ignora dependencias externas`() {
        assertFalse(
            corpoDe("liveness").contains("dynamoDb"),
            "a liveness não pode depender de infraestrutura externa",
        )
    }

    @Test
    fun `expoe o indicador do dynamodb no health geral`() {
        assertTrue(corpoDe("").contains("dynamoDb"))
    }
}
