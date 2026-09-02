package in.gov.ipie.common.security.keycloak;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * Wires {@link KeycloakTokenClient} only when {@code ipie.security.keycloak.token-uri} is
 * actually configured - a service that depends on {@code common-security} but has not configured
 * this still starts normally with no {@link KeycloakTokenClient} bean at all (see {@code
 * common-client}'s {@code InterServiceClientAutoConfiguration} Javadoc for why a bean must never
 * fail a service's startup just because the dependency is present - the same reasoning applies
 * here).
 */
@AutoConfiguration
@EnableConfigurationProperties(KeycloakTokenProperties.class)
public class KeycloakTokenAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ipie.security.keycloak", name = "token-uri")
    public KeycloakTokenClient keycloakTokenClient(RestClient.Builder restClientBuilder, KeycloakTokenProperties properties) {
        return new KeycloakTokenClient(restClientBuilder, properties);
    }
}
