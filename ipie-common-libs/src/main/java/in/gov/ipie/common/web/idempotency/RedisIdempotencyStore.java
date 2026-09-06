package in.gov.ipie.common.web.idempotency;

import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Real {@link IdempotencyStore}: {@code StoredResponse} is serialized (via the application's own
 * {@link ObjectMapper}, so it honours the same Jackson config as the rest of the service - the
 * same reasoning {@code RedisCacheConfig} documents for its own value serialization) and written
 * with Redis's atomic {@code SET key value NX EX ttl} ({@code opsForValue().setIfAbsent}) - first
 * writer wins, so two concurrent requests carrying the same {@code Idempotency-Key} never race
 * into a storage-layer exception the way the old per-service, Postgres-`@Id`-backed version could.
 * Works unchanged against a self-hosted Redis, AWS ElastiCache/MemoryDB, or any other
 * Redis-protocol-compatible target - only connection configuration differs, the same reasoning
 * {@link in.gov.ipie.common.security.hmac.RedisNonceStore RedisNonceStore} documents for itself.
 */
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final String KEY_PREFIX = "ipie:idempotency:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisIdempotencyStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<StoredResponse> find(String idempotencyKey) {
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + idempotencyKey);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, StoredResponse.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize a stored idempotent response", e);
        }
    }

    @Override
    public void store(String idempotencyKey, StoredResponse response, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + idempotencyKey, json, ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize an idempotent response for storage", e);
        }
    }
}
