package in.gov.ipie.common.security.hmac;

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
 * Wires {@link NonceStore}: {@link RedisNonceStore} when {@code spring.data.redis.host} is
 * configured (shared, multi-instance-safe replay protection), {@link InMemoryNonceStore}
 * otherwise - the same "real, shared-infrastructure binding when configured, safe in-process
 * fallback otherwise" precedence {@code common-cache}'s {@code IpieCacheAutoConfiguration} already
 * establishes for {@code CacheManager}. Neither {@link HmacSigningInterceptor}
 * ({@code common-client}) nor {@link HmacSignatureVerificationFilter} are wired here - both are
 * opt-in, applied by a service only to the specific high-sensitivity calls/endpoints that need
 * them (see their own Javadoc).
 */
@AutoConfiguration
@EnableConfigurationProperties(HmacSigningProperties.class)
public class HmacAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(NonceStore.class)
    public NonceStore inMemoryNonceStore() {
        return new InMemoryNonceStore();
    }

    /**
     * Kept as a separate, {@code @ConditionalOnClass}-guarded nested configuration - not a
     * {@code @Bean} method directly on the outer class - so that {@link RedisConnectionFactory}'s
     * absence from a consumer's classpath cannot fail classloading/verification of this
     * auto-configuration itself; only this nested class ever references a Redis type. See
     * {@code IpieCacheAutoConfiguration}'s Javadoc for the identical reasoning applied there.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RedisConnectionFactory.class)
    static class RedisNonceStoreConfig {

        @Bean
        @ConditionalOnProperty(prefix = "spring.data.redis", name = "host")
        @ConditionalOnMissingBean(NonceStore.class)
        NonceStore redisNonceStore(RedisConnectionFactory connectionFactory) {
            return new RedisNonceStore(new StringRedisTemplate(connectionFactory));
        }
    }
}
