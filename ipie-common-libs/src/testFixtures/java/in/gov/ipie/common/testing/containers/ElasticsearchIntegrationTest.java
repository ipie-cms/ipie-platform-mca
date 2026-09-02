package in.gov.ipie.common.testing.containers;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Mixin for repository/integration tests that need a real Elasticsearch instance (master
 * standards doc, 11: "Integration - Database, messaging and external adapters"). Pinned to the
 * same version ({@code 8.18.8}) that Spring Boot's BOM manages for the {@code co.elastic.clients}
 * Java client, so client and server stay wire-compatible - see {@code ipie-parent}'s
 * {@code approvedVersions.springBoot} for the Spring Boot version this must track. Security is
 * disabled, matching the local docker-compose Elasticsearch service.
 *
 * <p>Declared as an interface rather than an abstract class so a test needing both this and
 * {@link PostgresIntegrationTest} can {@code implement} both - Java doesn't allow extending two
 * base classes, but does allow implementing multiple interfaces. Testcontainers' JUnit 5
 * extension discovers {@code @Container} fields declared on any implemented interface, and
 * Spring's {@code @DynamicPropertySource} likewise resolves static methods from implemented
 * interfaces.
 */
@Testcontainers
@Tag("integration")
public interface ElasticsearchIntegrationTest {

    @Container
    ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer(DockerImageName.parse("elasticsearch:8.18.8"))
                    .withEnv("xpack.security.enabled", "false")
                    // Elasticsearch sizes its heap from the memory it can see, which in a container
                    // with no limit is the whole host - so on a developer machine it asks for
                    // several gigabytes it will not get and the container is OOMKilled before it
                    // ever logs "started". The test then fails as a wait-strategy timeout, which
                    // reads like a slow start rather than a memory ceiling. 512m is ample for a
                    // test index of a few documents.
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                    // Single node, so it does not wait on a cluster that will never form.
                    .withEnv("discovery.type", "single-node");

    @DynamicPropertySource
    static void elasticsearchProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", ELASTICSEARCH::getHttpHostAddress);
    }
}
