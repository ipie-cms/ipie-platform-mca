package in.gov.ipie.common.session.store;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Real {@link SessionStore}: stores the session's expiry instant as the Redis value under a
 * per-user key, with Redis's own TTL as the expiry mechanism - the key simply stops existing once
 * {@code ttl} elapses, so {@link #expiresAt} finding nothing already means "expired or never
 * existed" without this class tracking expiry itself. Works unchanged against a self-hosted
 * Redis, AWS ElastiCache/MemoryDB, or any other Redis-protocol-compatible target - only connection
 * configuration differs, the same reasoning {@code common-cache}'s {@code RedisCacheConfig} and
 * {@code common-security}'s {@code RedisNonceStore} document for their own Redis bindings. Shared
 * across every service instance, so - unlike {@link InMemorySessionStore} - this correctly holds
 * one consistent idle-session state in a horizontally scaled deployment.
 */
public class RedisSessionStore implements SessionStore {

    private static final String KEY_PREFIX = "ipie:session:";

    private final StringRedisTemplate redisTemplate;

    public RedisSessionStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void put(String userId, Duration ttl) {
        redisTemplate.opsForValue().set(key(userId), Instant.now().plus(ttl).toString(), ttl);
    }

    @Override
    public Optional<Instant> expiresAt(String userId) {
        String value = redisTemplate.opsForValue().get(key(userId));
        return value == null ? Optional.empty() : Optional.of(Instant.parse(value));
    }

    @Override
    public void remove(String userId) {
        redisTemplate.delete(key(userId));
    }

    private static String key(String userId) {
        return KEY_PREFIX + userId;
    }
}
