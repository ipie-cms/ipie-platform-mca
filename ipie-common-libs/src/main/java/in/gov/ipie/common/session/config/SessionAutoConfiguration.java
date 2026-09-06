package in.gov.ipie.common.session.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import in.gov.ipie.common.security.context.CurrentUserProvider;
import in.gov.ipie.common.session.SessionService;
import in.gov.ipie.common.session.store.InMemorySessionStore;
import in.gov.ipie.common.session.store.RedisSessionStore;
import in.gov.ipie.common.session.store.SessionStore;
import in.gov.ipie.common.session.web.SessionActivityFilter;
import in.gov.ipie.common.session.web.SessionController;

/**
 * Wires idle-session management for every service that depends on {@code common-session}, on by
 * default ({@code ipie.session.enabled=true}, see {@link SessionProperties}'s Javadoc):
 * {@link SessionStore} (Redis-backed when {@code spring.data.redis.host} is configured, in-memory
 * fallback otherwise - the same precedence {@code common-cache}/{@code common-security}'s
 * {@code NonceStore} already use), {@link SessionService}, the per-request
 * {@link SessionActivityFilter}, and the {@link SessionController} REST surface.
 */
@AutoConfiguration
@EnableConfigurationProperties(SessionProperties.class)
@ConditionalOnProperty(prefix = "ipie.session", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SessionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SessionStore.class)
    public SessionStore inMemorySessionStore() {
        return new InMemorySessionStore();
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
    static class RedisSessionStoreConfig {

        @Bean
        @ConditionalOnProperty(prefix = "spring.data.redis", name = "host")
        @ConditionalOnMissingBean(SessionStore.class)
        SessionStore redisSessionStore(RedisConnectionFactory connectionFactory) {
            return new RedisSessionStore(new StringRedisTemplate(connectionFactory));
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionService sessionService(SessionStore sessionStore, SessionProperties properties) {
        return new SessionService(sessionStore, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionController sessionController(SessionService sessionService, CurrentUserProvider currentUserProvider) {
        return new SessionController(sessionService, currentUserProvider);
    }

    @Bean
    public FilterRegistrationBean<SessionActivityFilter> sessionActivityFilterRegistration(
            SessionService sessionService, CurrentUserProvider currentUserProvider) {
        FilterRegistrationBean<SessionActivityFilter> registration =
                new FilterRegistrationBean<>(new SessionActivityFilter(sessionService, currentUserProvider));
        // Deliberately low priority (runs late) - see SessionActivityFilter's Javadoc: it must
        // run after Spring Security's own filter chain has already populated the
        // SecurityContext, since it reads the current user through CurrentUserProvider.
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        return registration;
    }
}
