package in.gov.ipie.common.web.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fallback {@link IdempotencyStore} when no Redis connection is configured - single-JVM only, so
 * a retried request landing on a different instance behind a load balancer would not see the
 * first instance's stored response (see {@link RedisIdempotencyStore} for the shared-state
 * implementation every multi-instance deployment should use instead). Suitable for a
 * single-instance/local-dev setup, or as a safe "still functions, just without cross-instance
 * replay" fallback rather than {@code @Idempotent} being silently inert.
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private record Entry(IdempotencyStore.StoredResponse response, Instant expiresAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService evictor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "idempotency-store-evictor");
        thread.setDaemon(true);
        return thread;
    });

    public InMemoryIdempotencyStore() {
        evictor.scheduleAtFixedRate(this::evictExpired, 1, 1, TimeUnit.MINUTES);
    }

    @Override
    public Optional<StoredResponse> find(String idempotencyKey) {
        Entry entry = entries.get(idempotencyKey);
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(entry.response());
    }

    @Override
    public void store(String idempotencyKey, StoredResponse response, Duration ttl) {
        // First writer wins - matches RedisIdempotencyStore's setIfAbsent semantics, so behavior
        // is identical regardless of which store backs a given deployment.
        entries.putIfAbsent(idempotencyKey, new Entry(response, Instant.now().plus(ttl)));
    }

    private void evictExpired() {
        Instant now = Instant.now();
        entries.values().removeIf(entry -> entry.expiresAt().isBefore(now));
    }
}
