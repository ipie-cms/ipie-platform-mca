package in.gov.ipie.common.security.hmac;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import in.gov.ipie.common.testing.containers.RedisIntegrationTest;

/**
 * Proves {@link RedisNonceStore} against a real Redis instance (Testcontainers) - the atomic
 * set-if-absent semantics that make it safe across multiple service instances, not just a single
 * JVM (contrast {@link InMemoryNonceStore}).
 */
class RedisNonceStoreTest implements RedisIntegrationTest {

    private final RedisNonceStore store = new RedisNonceStore(new StringRedisTemplate(connectionFactory()));

    @Test
    void tryConsume_returnsTrueTheFirstTimeANonceIsSeen() {
        assertThat(store.tryConsume("nonce-" + System.nanoTime(), Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void tryConsume_returnsFalseForAnAlreadyConsumedNonce() {
        String nonce = "nonce-" + System.nanoTime();
        store.tryConsume(nonce, Duration.ofMinutes(5));

        assertThat(store.tryConsume(nonce, Duration.ofMinutes(5))).isFalse();
    }

    private LettuceConnectionFactory connectionFactory() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        return factory;
    }
}
