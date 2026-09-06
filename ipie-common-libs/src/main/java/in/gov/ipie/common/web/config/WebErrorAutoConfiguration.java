package in.gov.ipie.common.web.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import in.gov.ipie.common.i18n.MessageResolver;
import in.gov.ipie.common.web.error.GlobalExceptionHandler;
import org.springframework.beans.factory.ObjectProvider;
import in.gov.ipie.common.persistence.IntegrityViolations;

/**
 * Registers {@link GlobalExceptionHandler} regardless of the consuming service's base package.
 *
 * <p>A plain {@code @RestControllerAdvice} in a shared library is NOT picked up by a service's
 * component scan - Spring Boot's {@code @SpringBootApplication} scan is rooted at the
 * application class's own package (e.g. {@code in.gov.ipie.service.template}), which never includes
 * {@code in.gov.ipie.common.web}. Registering the bean here, through the auto-configuration
 * mechanism (see {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}),
 * is what makes it load in every service without each one needing its own {@code @ComponentScan}.
 */
@AutoConfiguration
public class WebErrorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(GlobalExceptionHandler.class)
    public GlobalExceptionHandler globalExceptionHandler(MessageResolver messageResolver,
            ObjectProvider<IntegrityViolations> integrityViolations) {
        return new GlobalExceptionHandler(messageResolver, integrityViolations);
    }
}
