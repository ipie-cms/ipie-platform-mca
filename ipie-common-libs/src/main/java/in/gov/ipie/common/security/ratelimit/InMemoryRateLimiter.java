package in.gov.ipie.common.security.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fallback {@link RateLimiter} when no Redis connection is configured - single-JVM only, so it
 * only throttles within one instance, not across a horizontally scaled deployment (see {@link
 * RedisRateLimiter} for the shared-state implementation every multi-instance deployment should
 * use instead). Suitable for a single-instance/local-dev setup, or as a safe "still functions,
 * just with reduced protection" fallback rather than the endpoint being unprotected outright.
 */
public class InMemoryRateLimiter implements RateLimiter {

    private record Window(int count, Instant resetAt) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final ScheduledExecutorService evictor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "rate-limiter-evictor");
        thread.setDaemon(true);
        return thread;
    });

    public InMemoryRateLimiter() {
        evictor.scheduleAtFixedRate(this::evictExpired, 1, 1, TimeUnit.MINUTES);
    }

    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        Instant now = Instant.now();
        Window updated = windows.compute(key, (unusedKey, existing) -> {
            if (existing == null || existing.resetAt().isBefore(now)) {
                return new Window(1, now.plus(window));
            }
            return new Window(existing.count() + 1, existing.resetAt());
        });
        return updated.count() <= limit;
    }

    private void evictExpired() {
        Instant now = Instant.now();
        windows.values().removeIf(w -> w.resetAt().isBefore(now));
    }
}
