package br.com.itau.challenge.balance

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import com.lemonappdev.konsist.api.architecture.Layer
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

private const val DOMAIN_PACKAGE = "br.com.itau.challenge.balance.domain.."
private const val APPLICATION_PACKAGE = "br.com.itau.challenge.balance.application.."
private const val PORT_PACKAGE = "br.com.itau.challenge.balance.port.."

/** The layers that make up the core — none of them may know about any specific technology. */
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
     * The core must not know which database is behind the ports. Without this, an
     * `import software.amazon.awssdk...` could drift into a use case and quietly weld the
     * business rules to DynamoDB — the exact coupling the hexagon exists to prevent.
     */
    @Test
    fun `core does not depend on the AWS SDK`() {
        CORE_PACKAGES.forEach { assertNoImportsStartingWith(it, "software.amazon.awssdk") }
    }

    /**
     * Same argument for the messaging technology: the ingestion use case is about projecting a
     * balance, not about Kafka. Kafka belongs to one driving adapter and must stay there.
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
