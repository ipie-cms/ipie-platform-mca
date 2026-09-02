package in.gov.ipie.common.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import in.gov.ipie.common.testing.containers.RedisIntegrationTest;

/**
 * Proves {@link RedisRateLimiter} against a real Redis instance (Testcontainers) - the
 * fixed-window counter shared across instances (contrast {@link InMemoryRateLimiter}, single-JVM
 * only).
 */
class RedisRateLimiterTest implements RedisIntegrationTest {

    private final RedisRateLimiter limiter = new RedisRateLimiter(new StringRedisTemplate(connectionFactory()));

    @Test
    void tryAcquire_allowsUpToTheLimit_thenRejects() {
        String key = "key-" + System.nanoTime();

        assertThat(limiter.tryAcquire(key, 3, Duration.ofMinutes(1))).isTrue();
        assertThat(limiter.tryAcquire(key, 3, Duration.ofMinutes(1))).isTrue();
        assertThat(limiter.tryAcquire(key, 3, Duration.ofMinutes(1))).isTrue();
        assertThat(limiter.tryAcquire(key, 3, Duration.ofMinutes(1))).isFalse();
    }

    @Test
    void tryAcquire_treatsDifferentKeysIndependently() {
        String keyA = "key-a-" + System.nanoTime();
        String keyB = "key-b-" + System.nanoTime();

        assertThat(limiter.tryAcquire(keyA, 1, Duration.ofMinutes(1))).isTrue();
        assertThat(limiter.tryAcquire(keyA, 1, Duration.ofMinutes(1))).isFalse();
        assertThat(limiter.tryAcquire(keyB, 1, Duration.ofMinutes(1))).isTrue();
    }

    private LettuceConnectionFactory connectionFactory() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        return factory;
    }
}
