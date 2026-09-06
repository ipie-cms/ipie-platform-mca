package in.gov.ipie.common.cache.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Auto-configures Spring's cache abstraction ({@code @Cacheable}/{@code @CacheEvict}/
 * {@code @CachePut}) for every service that depends on {@code common-cache}, with zero
 * per-service YAML: a Redis-backed {@link CacheManager} when {@code spring.data.redis.host} is
 * configured, a no-op one otherwise - the same precedence style {@code EventPublisherConfig} uses
 * for Kafka/RabbitMQ (real binding when configured, harmless fallback when not), except here the
 * fallback is always safe: caching is an optimisation, so a method annotated {@code @Cacheable}
 * still runs correctly (just uncached) when no cache backend is present, e.g. a bare
 * {@code java -jar} run with no {@code SPRING_DATA_REDIS_HOST}.
 *
 * <p><b>The Redis starter is opt-in per service, not bundled by this module.</b> {@code
 * common-cache} depends on {@code spring-boot-starter-data-redis} as {@code compileOnly} only -
 * enough for {@link RedisCacheConfig} below to compile against {@link RedisConnectionFactory}/
 * {@link RedisCacheManager}, but not enough to put those classes on a consumer's runtime
 * classpath. A service that wants Redis-backed caching adds {@code
 * spring-boot-starter-data-redis} itself (see {@code ipie-service-template}'s {@code
 * build.gradle}); a service that never adds it never gets {@link RedisConnectionFactory} on its
 * classpath at all, {@link RedisCacheConfig}'s {@code @ConditionalOnClass} then keeps that whole
 * nested configuration - and everything in it that references a Redis type - from being
 * evaluated, and {@link #noOpCacheManager} is the only {@link CacheManager} such a service ever
 * gets. {@code @Cacheable}/{@code @CacheEvict} usage in application code is unaffected either way.
 *
 * <p>Application code only ever depends on {@code @Cacheable}/{@code @CacheEvict}/{@link
 * CacheManager} - Spring's own vendor-neutral abstraction, not a bespoke iPIE interface - so
 * swapping the backend is always a configuration (or, for adding Redis in the first place, a
 * one-line dependency) change, never an application-code change:
 * <ul>
 *   <li><b>AWS ElastiCache for Redis / MemoryDB for Redis</b>: both speak the Redis protocol, so
 *       pointing {@code spring.data.redis.host}/{@code port}/{@code ssl.enabled} at the managed
 *       endpoint works unchanged against {@link RedisCacheConfig#redisCacheManager} - the same
 *       reasoning as {@code S3FileStorage} covering AWS S3 and MinIO through configuration
 *       alone.</li>
 *   <li><b>Jedis instead of Lettuce</b>: which Redis client library backs {@link
 *       RedisConnectionFactory} is itself a Spring Boot autoconfiguration choice
 *       ({@code spring.data.redis.client-type=jedis}, once {@code redis.clients:jedis} is added as
 *       a runtime dependency) - {@link RedisCacheConfig} only ever consumes the
 *       already-autoconfigured {@link RedisConnectionFactory}, never a concrete client type, so
 *       neither bean changes either way.</li>
 *   <li><b>A cache that does not speak the Redis protocol at all</b> (a different cloud-native
 *       provider's own API): a second {@link CacheManager} implementation, registered the same way
 *       as {@link RedisCacheConfig} - its own nested, {@code @ConditionalOnClass}-guarded
 *       configuration with a {@code @Bean} guarded by its own {@code @ConditionalOnProperty}, ahead
 *       of or behind {@code redisCacheManager} in the precedence chain - not a rewrite of anything
 *       that calls it, the same pattern {@code FileStorage} documents for a non-S3 object
 *       store.</li>
 * </ul>
 *
 * <p>Ordered explicitly {@code before} Spring Boot's own {@link CacheAutoConfiguration} so one of
 * this class's own {@link CacheManager} beans is always registered first; {@code
 * CacheAutoConfiguration}'s own auto-detected {@code CacheManager} is itself {@code
 * @ConditionalOnMissingBean}, so it then backs off entirely instead of racing this class on
 * whatever order autoconfigurations happen to load in.
 */
@AutoConfiguration(before = CacheAutoConfiguration.class)
@EnableConfigurationProperties(IpieCacheProperties.class)
public class IpieCacheAutoConfiguration {

    /**
     * Fallback when no cache backend is configured (Redis absent from the classpath entirely, or
     * present but {@code spring.data.redis.host} unset) - every {@code @Cacheable} method still
     * runs, just uncached, so a bare local run behaves correctly without Redis present.
     *
     * @return a {@link NoOpCacheManager} that never stores anything
     */
    @Bean
    @ConditionalOnMissingBean(CacheManager.class)
    public CacheManager noOpCacheManager() {
        return new NoOpCacheManager();
    }

    /**
     * Kept as a separate, {@code @ConditionalOnClass}-guarded nested configuration - not a
     * {@code @Bean} method directly on the outer class - so that {@link RedisConnectionFactory}'s
     * absence from a consumer's classpath (a service that never added {@code
     * spring-boot-starter-data-redis}) cannot fail classloading/verification of this
     * auto-configuration itself; only this nested class ever references a Redis type. See the
     * outer class's Javadoc for the full opt-in-per-service reasoning.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RedisConnectionFactory.class)
    static class RedisCacheConfig {

        /**
         * Builds the Redis-backed {@link CacheManager}: JSON value serialization (via the
         * application's own {@link ObjectMapper}, so it honours the same Jackson modules/config as
         * the rest of the service - e.g. {@code JavaTimeModule} - rather than a second,
         * inconsistent serialization story), plain string keys, and null values never cached (a
         * cache miss must stay a miss on the next lookup, not be remembered as "definitely absent"
         * forever). {@code properties.getTtls()} entries override {@code
         * properties.getDefaultTtl()} per cache name, e.g. {@code ipie.cache.ttls.user-lookup=PT1M}.
         *
         * @param connectionFactory Spring Boot's autoconfigured Redis connection (Lettuce by
         *     default; see the enclosing class's Javadoc for the Jedis switch) - only
         *     autoconfigured at all once a service adds {@code spring-boot-starter-data-redis}
         * @param objectMapper the application's shared Jackson mapper, reused for cache value
         *     serialization instead of standing up a second one
         * @param properties the {@code ipie.cache.*} TTL configuration
         * @return a {@link RedisCacheManager} ready to back every {@code @Cacheable} in the
         *     service
         */
        @Bean
        @ConditionalOnProperty(prefix = "spring.data.redis", name = "host")
        @ConditionalOnMissingBean(CacheManager.class)
        CacheManager redisCacheManager(
                RedisConnectionFactory connectionFactory, ObjectMapper objectMapper, IpieCacheProperties properties) {
            RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(properties.getDefaultTtl())
                    .disableCachingNullValues()
                    .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(
                            new StringRedisSerializer()))
                    .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                            new GenericJackson2JsonRedisSerializer(objectMapper)));
            return RedisCacheManager.builder(connectionFactory)
                    .cacheDefaults(defaultConfig)
                    .withInitialCacheConfigurations(perCacheOverrides(defaultConfig, properties))
                    .build();
        }

        /**
         * Maps each {@code ipie.cache.ttls.<name>} override onto its own {@link
         * RedisCacheConfiguration} - identical serialization to {@code defaultConfig}, only the
         * TTL differs - so {@link RedisCacheManager} applies a per-cache-name lifetime instead of
         * one blanket TTL for every {@code @Cacheable} in the service.
         *
         * @param defaultConfig the base configuration (serialization + default TTL) each override
         *     starts from
         * @param properties supplies the {@code name -> TTL} overrides to build
         * @return one {@link RedisCacheConfiguration} per overridden cache name
         */
        private Map<String, RedisCacheConfiguration> perCacheOverrides(
                RedisCacheConfiguration defaultConfig, IpieCacheProperties properties) {
            Map<String, RedisCacheConfiguration> overrides = new HashMap<>();
            for (Entry<String, Duration> ttl : properties.getTtls().entrySet()) {
                overrides.put(ttl.getKey(), defaultConfig.entryTtl(ttl.getValue()));
            }
            return overrides;
        }
    }
}
