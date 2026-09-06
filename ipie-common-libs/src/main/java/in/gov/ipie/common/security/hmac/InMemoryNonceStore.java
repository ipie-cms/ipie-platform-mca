package in.gov.ipie.common.security.hmac;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fallback {@link NonceStore} when no Redis connection is configured - single-JVM only, so it
 * only protects against replay within one instance, not across a horizontally scaled deployment
 * (see {@link RedisNonceStore} for the real, shared-state implementation every multi-instance
 * deployment should use instead). Suitable for a single-instance/local-dev setup, or as a safe
 * "still functions, just with reduced protection" fallback rather than the endpoint being
 * unprotected outright.
 *
 * <p>Selecting this store is announced at WARN on construction. The reduced protection is
 * otherwise invisible at runtime: replay attempts simply succeed whenever they land on an instance
 * that has not seen the nonce, with nothing logged and no metric moved. An operator who scales a
 * service to more than one replica without configuring {@code spring.data.redis.host} would
 * silently lose a security control they have every reason to believe is active - so it says so
 * itself, the same way {@code LoggingSmsServiceImpl} announces that it is a placeholder rather
 * than letting a "SENT" row imply a delivery that never happened.
 */
public class InMemoryNonceStore implements NonceStore {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryNonceStore.class);

    private final Map<String, Instant> consumedAt = new ConcurrentHashMap<>();
    private final ScheduledExecutorService evictor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "hmac-nonce-store-evictor");
        thread.setDaemon(true);
        return thread;
    });

    public InMemoryNonceStore() {
        LOG.warn("HMAC replay protection is using the in-memory nonce store: it is single-JVM only. "
                + "Replay is prevented within this instance but NOT across replicas - configure "
                + "spring.data.redis.host so RedisNonceStore is used before running more than one "
                + "instance of this service.");
        evictor.scheduleAtFixedRate(this::evictExpired, 1, 1, TimeUnit.MINUTES);
    }

    @Override
    public boolean tryConsume(String nonce, Duration ttl) {
        // A nonce is meant to be used exactly once, ever - presence alone (even if its TTL has
        // technically elapsed but the periodic sweep hasn't reached it yet) is treated as replay;
        // deliberately simpler/more conservative than reclaiming expired slots early, since a
        // legitimate caller never intentionally reuses a nonce value.
        return consumedAt.putIfAbsent(nonce, Instant.now().plus(ttl)) == null;
    }

    private void evictExpired() {
        Instant now = Instant.now();
        consumedAt.values().removeIf(expiry -> expiry.isBefore(now));
    }
}
