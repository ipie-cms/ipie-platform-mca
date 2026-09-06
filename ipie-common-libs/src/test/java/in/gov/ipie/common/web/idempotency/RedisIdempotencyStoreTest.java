package in.gov.ipie.common.web.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import in.gov.ipie.common.testing.containers.RedisIntegrationTest;

/**
 * Proves {@link RedisIdempotencyStore} against a real Redis instance (Testcontainers) - the
 * atomic set-if-absent semantics that make a concurrent duplicate request a harmless no-op instead
 * of the storage-layer exception the old, Postgres-`@Id`-backed version could raise.
 */
class RedisIdempotencyStoreTest implements RedisIntegrationTest {

    private final RedisIdempotencyStore store = new RedisIdempotencyStore(new StringRedisTemplate(connectionFactory()), new ObjectMapper());

    @Test
    void find_returnsEmpty_whenNothingStoredForThatKey() {
        assertThat(store.find("key-" + System.nanoTime())).isEmpty();
    }

    @Test
    void find_returnsTheStoredResponse_afterStore() {
        String key = "key-" + System.nanoTime();
        IdempotencyStore.StoredResponse response = new IdempotencyStore.StoredResponse(201, "{\"id\":\"abc\"}");

        store.store(key, response, Duration.ofMinutes(5));

        assertThat(store.find(key)).contains(response);
    }

    @Test
    void store_firstWriterWins_whenTheSameKeyIsStoredTwice() {
        String key = "key-" + System.nanoTime();
        IdempotencyStore.StoredResponse first = new IdempotencyStore.StoredResponse(201, "{\"id\":\"first\"}");
        IdempotencyStore.StoredResponse second = new IdempotencyStore.StoredResponse(201, "{\"id\":\"second\"}");

        store.store(key, first, Duration.ofMinutes(5));
        store.store(key, second, Duration.ofMinutes(5));

        assertThat(store.find(key)).contains(first);
    }

    private LettuceConnectionFactory connectionFactory() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        return factory;
    }
}
