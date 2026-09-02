package in.gov.ipie.common.cache.config;

import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import in.gov.ipie.common.cache.config.IpieCacheAutoConfiguration.RedisCacheConfig;
import in.gov.ipie.common.testing.containers.RedisIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link IpieCacheAutoConfiguration} against a real Redis instance (Testcontainers), not
 * just by inspection: the Redis-backed {@link CacheManager} actually round-trips a value through
 * real Redis, honours a per-cache-name TTL override, and the no-op fallback never throws even
 * though it never actually caches anything.
 */
class IpieCacheAutoConfigurationTest implements RedisIntegrationTest {

    private final IpieCacheAutoConfiguration autoConfiguration = new IpieCacheAutoConfiguration();
    private final RedisCacheConfig redisCacheConfig = new RedisCacheConfig();

    @Test
    void redisCacheManagerRoundTripsAValueThroughRealRedis() throws Exception {
        CacheManager cacheManager =
                buildInitializedRedisCacheManager(new ObjectMapper(), new IpieCacheProperties());
        Cache cache = cacheManager.getCache("test-cache");

        cache.put("key-1", "value-1");

        assertThat(cache.get("key-1", String.class)).isEqualTo("value-1");
    }

    @Test
    void redisCacheManagerAppliesPerCacheNameTtlOverride() throws Exception {
        IpieCacheProperties properties = new IpieCacheProperties();
        properties.setDefaultTtl(Duration.ofMinutes(10));
        properties.setTtls(Map.of("short-lived", Duration.ofSeconds(30)));
        CacheManager cacheManager = buildInitializedRedisCacheManager(new ObjectMapper(), properties);

        // Asserts on the resolved configuration rather than waiting out a real expiry - real
        // expiry timing depends on the Redis container's own clock/scheduling and isn't something
        // a unit test should be sensitive to; this proves the override is wired through without
        // that flakiness risk.
        RedisCache shortLived = (RedisCache) cacheManager.getCache("short-lived");
        RedisCache defaulted = (RedisCache) cacheManager.getCache("test-cache");

        assertThat(shortLived.getCacheConfiguration().getTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaulted.getCacheConfiguration().getTtl()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void noOpCacheManagerNeverCachesButNeverThrows() {
        CacheManager cacheManager = autoConfiguration.noOpCacheManager();
        Cache cache = cacheManager.getCache("anything");

        cache.put("key-1", "value-1");

        assertThat(cache.get("key-1")).isNull();
        assertThat(cacheManager).isInstanceOf(NoOpCacheManager.class);
    }

    /**
     * {@link RedisCacheManager} implements {@link InitializingBean} - {@code
     * initializeCaches()} (which pre-creates every named cache from {@code
     * withInitialCacheConfigurations}, per-name TTL overrides included) only runs via that
     * callback. A real {@code ApplicationContext} invokes it automatically after the {@code @Bean}
     * method returns; calling {@link RedisCacheConfig#redisCacheManager} directly, as a plain
     * method, does not - so this test must trigger it explicitly or every cache silently falls
     * back to {@code cacheDefaults()} regardless of any override.
     */
    private CacheManager buildInitializedRedisCacheManager(ObjectMapper objectMapper, IpieCacheProperties properties)
            throws Exception {
        CacheManager cacheManager = redisCacheConfig.redisCacheManager(connectionFactory(), objectMapper, properties);
        ((InitializingBean) cacheManager).afterPropertiesSet();
        return cacheManager;
    }

    private RedisConnectionFactory connectionFactory() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        return factory;
    }
}
