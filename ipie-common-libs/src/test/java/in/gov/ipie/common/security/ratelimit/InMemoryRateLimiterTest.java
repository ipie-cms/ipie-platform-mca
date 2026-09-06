package in.gov.ipie.common.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class InMemoryRateLimiterTest {

    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter();

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

    @Test
    void tryAcquire_resetsOnceTheWindowElapses() throws InterruptedException {
        String key = "key-" + System.nanoTime();
        Duration window = Duration.ofMillis(50);

        assertThat(limiter.tryAcquire(key, 1, window)).isTrue();
        assertThat(limiter.tryAcquire(key, 1, window)).isFalse();

        Thread.sleep(100);

        assertThat(limiter.tryAcquire(key, 1, window)).isTrue();
    }
}
