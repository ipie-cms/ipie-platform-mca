package in.gov.ipie.common.session.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import in.gov.ipie.common.testing.containers.RedisIntegrationTest;

/**
 * Proves {@link RedisSessionStore} against a real Redis instance (Testcontainers) - the
 * TTL-as-expiry mechanism that makes it safe across multiple service instances, not just a single
 * JVM (contrast {@link InMemorySessionStore}).
 */
class RedisSessionStoreTest implements RedisIntegrationTest {

    private final RedisSessionStore store = new RedisSessionStore(new StringRedisTemplate(connectionFactory()));

    @Test
    void expiresAt_roundTripsTheStoredExpiryInstant() {
        String userId = "user-" + System.nanoTime();
        store.put(userId, Duration.ofMinutes(15));

        Instant expiresAt = store.expiresAt(userId).orElseThrow();

        assertThat(expiresAt).isAfter(Instant.now().plus(Duration.ofMinutes(14)));
        assertThat(expiresAt).isBefore(Instant.now().plus(Duration.ofMinutes(16)));
    }

    @Test
    void expiresAt_forAnUnknownUser_isEmpty() {
        assertThat(store.expiresAt("never-touched-" + System.nanoTime())).isEmpty();
    }

    @Test
    void remove_endsTheSessionImmediately() {
        String userId = "user-" + System.nanoTime();
        store.put(userId, Duration.ofMinutes(15));

        store.remove(userId);

        assertThat(store.expiresAt(userId)).isEmpty();
    }

    private LettuceConnectionFactory connectionFactory() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        return factory;
    }
}
