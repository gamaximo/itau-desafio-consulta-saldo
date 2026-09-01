package br.com.itau.challenge.balance

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import com.lemonappdev.konsist.api.architecture.Layer
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

private const val DOMAIN_PACKAGE = "br.com.itau.challenge.balance.domain.."
private const val APPLICATION_PACKAGE = "br.com.itau.challenge.balance.application.."
private const val PORT_PACKAGE = "br.com.itau.challenge.balance.port.."

/** As camadas que formam o núcleo — nenhuma delas pode conhecer qualquer tecnologia específica. */
private val CORE_PACKAGES = listOf(DOMAIN_PACKAGE, APPLICATION_PACKAGE, PORT_PACKAGE)

class HexagonalArchitectureTest {

    private val scope = Konsist.scopeFromPackage("br.com.itau.challenge.balance..")

    private val domain = Layer("Domain", "..balance.domain..")
    private val port = Layer("Port", "..balance.port..")
    private val application = Layer("Application", "..balance.application..")
    private val adapter = Layer("Adapter", "..balance.adapter..")

    @Test
    fun `hexagonal layers respect dependency direction`() {
        scope.assertArchitecture {
            domain.dependsOnNothing()
            port.doesNotDependOn(application, adapter)
            application.doesNotDependOn(adapter)
        }
    }

    @Test
    fun `domain does not depend on the Spring framework`() {
        assertNoImportsStartingWith(DOMAIN_PACKAGE, "org.springframework")
    }

    /**
     * O núcleo não pode saber qual banco está atrás das portas. Sem isto, um
     * `import software.amazon.awssdk...` poderia se infiltrar num caso de uso e soldar as regras
     * de negócio ao DynamoDB em silêncio — exatamente o acoplamento que o hexágono existe para
     * evitar.
     */
    @Test
    fun `core does not depend on the AWS SDK`() {
        CORE_PACKAGES.forEach { assertNoImportsStartingWith(it, "software.amazon.awssdk") }
    }

    /**
     * O mesmo argumento vale para a tecnologia de mensageria: o caso de uso de ingestão trata de
     * projetar um saldo, não de Kafka. O Kafka pertence a um adaptador de entrada e tem que ficar
     * lá.
     */
    @Test
    fun `core does not depend on Kafka`() {
        CORE_PACKAGES.forEach { assertNoImportsStartingWith(it, "org.apache.kafka") }
    }

    private fun assertNoImportsStartingWith(
        packageName: String,
        forbiddenPrefix: String,
    ) = Konsist
        .scopeFromPackage(packageName)
        .files
        .assertFalse { file -> file.hasImport { import -> import.name.startsWith(forbiddenPrefix) } }
}
