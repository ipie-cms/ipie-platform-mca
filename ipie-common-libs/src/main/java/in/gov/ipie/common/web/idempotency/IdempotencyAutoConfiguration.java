package in.gov.ipie.common.web.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Wires {@link IdempotencyStore}: {@link RedisIdempotencyStore} when
 * {@code spring.data.redis.host} is configured (shared, multi-instance-safe replay), {@link
 * InMemoryIdempotencyStore} otherwise - the same precedence {@code common-security}'s {@code
 * HmacAutoConfiguration}/{@code NonceStore} and {@code common-cache}'s {@code
 * IpieCacheAutoConfiguration}/{@code CacheManager} already establish. Also wires {@link
 * IdempotencyAspect} - every service that depends on {@code common-web} gets {@code @Idempotent}
 * enforcement automatically, with no per-service storage of its own to maintain.
 */
@AutoConfiguration
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    public IdempotencyStore inMemoryIdempotencyStore() {
        return new InMemoryIdempotencyStore();
    }

    /**
     * Kept as a separate, {@code @ConditionalOnClass}-guarded nested configuration - not a
     * {@code @Bean} method directly on the outer class - so that {@link RedisConnectionFactory}'s
     * absence from a consumer's classpath cannot fail classloading/verification of this
     * auto-configuration itself; only this nested class ever references a Redis type. See {@code
     * IpieCacheAutoConfiguration}'s Javadoc for the identical reasoning applied there.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RedisConnectionFactory.class)
    static class RedisIdempotencyStoreConfig {

        @Bean
        @ConditionalOnProperty(prefix = "spring.data.redis", name = "host")
        @ConditionalOnMissingBean(IdempotencyStore.class)
        IdempotencyStore redisIdempotencyStore(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
            return new RedisIdempotencyStore(new StringRedisTemplate(connectionFactory), objectMapper);
        }
    }

    @Bean
    public IdempotencyAspect idempotencyAspect(
            IdempotencyStore idempotencyStore, IdempotencyProperties properties, ObjectMapper objectMapper) {
        return new IdempotencyAspect(idempotencyStore, properties, objectMapper);
    }
}
