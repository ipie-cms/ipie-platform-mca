package in.gov.ipie.common.session.store;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fallback {@link SessionStore} when no Redis connection is configured - single-JVM only, so a
 * horizontally scaled deployment would see a different idle-session state per instance (see
 * {@link RedisSessionStore} for the real, shared-state implementation every multi-instance
 * deployment should use instead). Suitable for local dev/a single-instance deployment.
 */
public class InMemorySessionStore implements SessionStore {

    private final Map<String, Instant> expiryByUserId = new ConcurrentHashMap<>();

    @Override
    public void put(String userId, Duration ttl) {
        expiryByUserId.put(userId, Instant.now().plus(ttl));
    }

    @Override
    public Optional<Instant> expiresAt(String userId) {
        Instant expiry = expiryByUserId.get(userId);
        if (expiry == null) {
            return Optional.empty();
        }
        if (expiry.isBefore(Instant.now())) {
            expiryByUserId.remove(userId);
            return Optional.empty();
        }
        return Optional.of(expiry);
    }

    @Override
    public void remove(String userId) {
        expiryByUserId.remove(userId);
    }
}
