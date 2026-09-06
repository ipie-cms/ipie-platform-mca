package in.gov.ipie.common.i18n.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import in.gov.ipie.common.i18n.IpieI18nProperties;
import in.gov.ipie.common.i18n.MessageResolver;
import in.gov.ipie.common.i18n.SupportedLocaleResolver;
import in.gov.ipie.common.i18n.web.LocaleLoggingInterceptor;
import in.gov.ipie.common.resilience.config.YamlPropertySourceFactory;

/**
 * Wires internationalisation for every service that depends on common-i18n:
 *
 * <ul>
 *   <li>{@code ipie-i18n-defaults.yml} (loaded below) sets {@code spring.messages.basename} to
 *       {@code common-messages,messages} so Spring Boot's own {@code MessageSourceAutoConfiguration}
 *       - and, through it, Bean Validation's message interpolation - picks up both this module's
 *       shared system-message bundle and each service's own {@code messages_<lang>.properties}
 *       automatically. No service needs to declare {@code spring.messages.basename} itself for
 *       the common case; one that does replaces this default entirely, the same trade-off
 *       {@code ipie-resilience-defaults.yml} already documents for resilience4j instance config.</li>
 *   <li>{@link SupportedLocaleResolver} restricts locale resolution to
 *       {@link IpieI18nProperties#getSupportedLocales()}, defaulting to
 *       {@link IpieI18nProperties#getDefaultLocale()} for anything else.</li>
 *   <li>{@link MessageResolver} is the one way a service turns an
 *       {@link in.gov.ipie.common.core.exception.ErrorCode} into a localized string - see
 *       common-web's {@code GlobalExceptionHandler} for its main consumer.</li>
 *   <li>{@link LocaleLoggingInterceptor} publishes the resolved locale to the SLF4J MDC, the same
 *       treatment correlationId/traceId already get.</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(IpieI18nProperties.class)
@PropertySource(value = "classpath:ipie-i18n-defaults.yml", factory = YamlPropertySourceFactory.class)
public class I18nAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(LocaleResolver.class)
    public LocaleResolver localeResolver(IpieI18nProperties properties) {
        return new SupportedLocaleResolver(properties.getSupportedLocales(), properties.getDefaultLocale());
    }

    @Bean
    @ConditionalOnMissingBean(MessageResolver.class)
    public MessageResolver messageResolver(MessageSource messageSource) {
        return new MessageResolver(messageSource);
    }

    @Bean
    @ConditionalOnMissingBean(LocaleLoggingInterceptor.class)
    public LocaleLoggingInterceptor localeLoggingInterceptor() {
        return new LocaleLoggingInterceptor();
    }

    @Bean
    public WebMvcConfigurer i18nWebMvcConfigurer(LocaleLoggingInterceptor localeLoggingInterceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(localeLoggingInterceptor);
            }
        };
    }
}
