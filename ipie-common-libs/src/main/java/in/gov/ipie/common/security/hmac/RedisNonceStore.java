package in.gov.ipie.common.security.hmac;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Real {@link NonceStore}: Redis's atomic {@code SET key value NX PX millis} (exposed by Spring
 * Data Redis as {@code opsForValue().setIfAbsent(key, value, ttl)}) is exactly a "consume once
 * within a TTL" primitive, so this needs no extra locking of its own - the single Redis command
 * is the whole implementation. Works unchanged against a self-hosted Redis, AWS ElastiCache/
 * MemoryDB, or any other Redis-protocol-compatible target - only connection configuration
 * differs, the same reasoning {@code common-cache}'s {@code RedisCacheConfig} documents for its
 * own {@code CacheManager}. Shared across every service instance, so - unlike
 * {@link InMemoryNonceStore} - this correctly protects against replay in a horizontally scaled
 * deployment.
 */
public class RedisNonceStore implements NonceStore {

    private static final String KEY_PREFIX = "ipie:hmac:nonce:";

    private final StringRedisTemplate redisTemplate;

    public RedisNonceStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryConsume(String nonce, Duration ttl) {
        Boolean firstTimeSeen = redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + nonce, "1", ttl);
        return Boolean.TRUE.equals(firstTimeSeen);
    }
}
