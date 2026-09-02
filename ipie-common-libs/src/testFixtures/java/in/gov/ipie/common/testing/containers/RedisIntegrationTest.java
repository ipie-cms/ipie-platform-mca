package in.gov.ipie.common.testing.containers;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Mixin for tests that need a real Redis instance (master standards doc, 11: "Integration -
 * Database, messaging and external adapters"). Pinned to {@code redis:7-alpine}, the same image
 * {@code docker-compose.yml}'s {@code redis} service runs, for the same licensing reason
 * documented there (Redis 8 relicensed away from a purely permissive license).
 *
 * <p>No dedicated Testcontainers Redis module is used by this project's Testcontainers version, so
 * this uses {@link GenericContainer} directly with the well-known Redis port (6379) exposed -
 * declared as an interface, not an abstract class, for the same multi-container-composition reason
 * as {@link PostgresIntegrationTest}/{@link ElasticsearchIntegrationTest}.
 */
@Testcontainers
@Tag("integration")
public interface RedisIntegrationTest {

    @Container
    GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
