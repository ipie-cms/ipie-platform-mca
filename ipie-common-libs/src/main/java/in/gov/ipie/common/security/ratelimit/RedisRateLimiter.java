package in.gov.ipie.common.security.ratelimit;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Real {@link RateLimiter}: a fixed-window counter shared across every instance behind a load
 * balancer - {@code INCR} the key, and on the very first hit of a new window ({@code count == 1})
 * set its expiry so the window resets on its own. Works unchanged against a self-hosted Redis, AWS
 * ElastiCache/MemoryDB, or any other Redis-protocol-compatible target - only connection
 * configuration differs, the same reasoning {@code RedisNonceStore}/{@code RedisIdempotencyStore}
 * document for themselves.
 *
 * <p><b>Known tradeoff, deliberately accepted rather than reached for a Lua script</b>: {@code
 * INCR} and {@code EXPIRE} are two separate Redis round-trips, not one atomic command like {@code
 * RedisNonceStore}'s {@code SET ... NX}. If the process crashes between them, that key is left
 * with no expiry and never resets - every future request against that exact key is rejected until
 * something else removes it (a Redis restart, or the key eventually being evicted under memory
 * pressure). This is a narrow, low-probability window and the simplest implementation that solves
 * the actual problem (unbounded abuse of a public endpoint); a single atomic Lua-script version
 * would close it entirely if this tradeoff ever proves unacceptable in practice.
 */
public class RedisRateLimiter implements RateLimiter {

    private static final String KEY_PREFIX = "ipie:ratelimit:";

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        String redisKey = KEY_PREFIX + key;
        Long count = redisTemplate.opsForValue().increment(redisKey);
        if (count != null && count == 1L) {
            redisTemplate.expire(redisKey, window);
        }
        return count != null && count <= limit;
    }
}
