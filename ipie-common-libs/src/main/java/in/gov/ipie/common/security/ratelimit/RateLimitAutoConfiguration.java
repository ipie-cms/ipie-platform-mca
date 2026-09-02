package in.gov.ipie.common.security.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Wires {@link RateLimiter}: {@link RedisRateLimiter} when {@code spring.data.redis.host} is
 * configured (shared, multi-instance-safe throttling), {@link InMemoryRateLimiter} otherwise -
 * the same precedence {@code common-security}'s {@code HmacAutoConfiguration}/{@code NonceStore}
 * and {@code common-web}'s {@code IdempotencyAutoConfiguration}/{@code IdempotencyStore} already
 * establish. {@link RateLimitFilter} is deliberately **not** wired here - same split
 * {@code HmacAutoConfiguration}/{@code HmacSignatureVerificationFilter} already establishes: a
 * service registers the filter itself, in its own {@code SecurityFilterChain}, only for the
 * specific public paths that actually need it.
 */
@AutoConfiguration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(RateLimiter.class)
    public RateLimiter inMemoryRateLimiter() {
        return new InMemoryRateLimiter();
    }

    /**
     * Warns at startup when rate limiting is active but no proxy is trusted, because the two
     * settings interact in a way that is invisible until users complain.
     *
     * <p>Buckets are keyed on the caller's address. With no trusted proxies, that address is the
     * direct peer - which behind an ingress or API gateway is the gateway, the same value for
     * everyone. Every caller then shares ONE bucket per rule rather than having one each, and a
     * limit meant as "5 registrations per minute per user" silently becomes "5 per minute for the
     * whole platform". The empty default is the right one for safety (it is what makes
     * X-Forwarded-For unforgeable - see ipie-security-defaults.yml), so this is a prompt to set
     * IPIE_TRUSTED_PROXIES, not a reason to change the default.
     */
    @Bean
    ApplicationRunner rateLimitTrustedProxyCheck(RateLimitProperties properties, Environment environment) {
        return args -> {
            if (properties.getRules().isEmpty()) {
                return;
            }
            String trustedProxies = environment.getProperty("server.tomcat.remoteip.internal-proxies", "");
            if (trustedProxies == null || trustedProxies.isBlank()) {
                LOG.warn("Rate limiting is active on {} path(s) but no trusted proxies are configured. "
                        + "Behind an ingress or API gateway every caller presents the gateway's address, so all "
                        + "callers share one bucket per rule instead of one each. Set IPIE_TRUSTED_PROXIES to the "
                        + "ingress/gateway CIDR to key the limits on the real client.", properties.getRules().size());
            }
        };
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
    static class RedisRateLimiterConfig {

        @Bean
        @ConditionalOnProperty(prefix = "spring.data.redis", name = "host")
        @ConditionalOnMissingBean(RateLimiter.class)
        RateLimiter redisRateLimiter(RedisConnectionFactory connectionFactory) {
            return new RedisRateLimiter(new StringRedisTemplate(connectionFactory));
        }
    }
}
