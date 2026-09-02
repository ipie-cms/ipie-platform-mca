package in.gov.ipie.common.testing.containers;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Mixin for repository/integration tests that need a real PostgreSQL instance (master standards
 * doc, 11: "Integration - Database, messaging and external adapters"). One container is shared
 * across all tests in the JVM for speed; Spring's context caching keys on the injected properties
 * so tests across classes still share a single Spring context where possible.
 *
 * <p>Declared as an interface (Testcontainers' documented pattern for composing multiple
 * containers - see {@code ElasticsearchIntegrationTest}) rather than an abstract class, so a test
 * needing more than one container can {@code implement} several of these instead of being limited
 * to Java's single-class-inheritance.
 */
@Testcontainers
@Tag("integration")
public interface PostgresIntegrationTest {

    @Container
    PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
