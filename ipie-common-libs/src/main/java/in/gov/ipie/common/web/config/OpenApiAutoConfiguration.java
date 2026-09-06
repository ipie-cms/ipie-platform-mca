package in.gov.ipie.common.web.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * OpenAPI documentation is mandatory for every iPIE API (master standards doc, 5.3), and every
 * service declared it the same way - which is how all three ended up serving the template's own
 * description, "User CRUD reference implementation built from the approved iPIE service template",
 * as their public API summary.
 *
 * <p>The title comes from {@code spring.application.name} and the description from
 * {@code ipie.openapi.description}, so a service says what it is by configuring one line rather than
 * by copying a class and remembering to edit it.
 */
@AutoConfiguration
@ConditionalOnClass(OpenAPI.class)
public class OpenApiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(OpenAPI.class)
    public OpenAPI ipieOpenApi(
            @Value("${spring.application.name:ipie-service}") String serviceName,
            @Value("${ipie.openapi.description:}") String description,
            @Value("${ipie.openapi.version:v1}") String version) {
        return new OpenAPI().info(new Info()
                .title(serviceName)
                .description(description.isBlank() ? serviceName + " API" : description)
                .version(version));
    }
}
