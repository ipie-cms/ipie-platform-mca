package in.gov.ipie.common.resilience.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.web.client.RestClient;

/**
 * Wires the shared resilience defaults (Development_Environment_Configuration.md, Section 15,
 * Resilience) into every service that depends on {@code common-resilience}:
 *
 * <ul>
 *   <li>{@code ipie-resilience-defaults.yml} (loaded below) supplies the "default" Retry/
 *       CircuitBreaker/Bulkhead/TimeLimiter config that resilience4j-spring-boot3's own
 *       autoconfiguration applies to any {@code @Retry}/{@code @CircuitBreaker}/
 *       {@code @Bulkhead}/{@code @TimeLimiter} instance name a service does not explicitly
 *       configure - no per-service YAML required for the common case.</li>
 *   <li>{@link RestClientTimeoutConfiguration} applies an explicit connection/response timeout
 *       to every service's auto-configured {@code RestClient.Builder} - the "Timeout" control,
 *       covering blocking calls that a {@code TimeLimiter} alone does not bound.</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(IpieResilienceHttpProperties.class)
@PropertySource(value = "classpath:ipie-resilience-defaults.yml", factory = YamlPropertySourceFactory.class)
public class IpieResilienceAutoConfiguration {

    /**
     * Kept as a separate, {@code @ConditionalOnClass}-guarded nested configuration - not a
     * {@code @Bean} method directly on the outer class - so that {@code RestClient}'s absence
     * from a future consumer's classpath cannot fail classloading/verification of this
     * auto-configuration itself; only this nested class ever references that type.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestClient.class)
    static class RestClientTimeoutConfiguration {

        @Bean
        RestClientCustomizer ipieHttpTimeoutCustomizer(IpieResilienceHttpProperties properties) {
            return builder -> builder.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(
                    ClientHttpRequestFactorySettings.defaults()
                            .withConnectTimeout(properties.getConnectTimeout())
                            .withReadTimeout(properties.getResponseTimeout())));
        }
    }
}
