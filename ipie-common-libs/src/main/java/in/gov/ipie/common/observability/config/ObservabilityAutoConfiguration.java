package in.gov.ipie.common.observability.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;

import in.gov.ipie.common.observability.correlation.CorrelationIdFilter;
import in.gov.ipie.common.resilience.config.YamlPropertySourceFactory;

/**
 * Registers the correlation id filter for every service that pulls in common-observability, and
 * loads {@code ipie-observability-defaults.yml} - which turns on Tomcat's MBean registry so
 * {@code tomcat.threads.*} is actually published. See that file for why it matters.
 */
@AutoConfiguration
@PropertySource(value = "classpath:ipie-observability-defaults.yml", factory = YamlPropertySourceFactory.class)
public class ObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CorrelationIdFilter.class)
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration(CorrelationIdFilter filter) {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(filter.getOrder());
        registration.addUrlPatterns("/*");
        return registration;
    }
}
