package in.gov.ipie.common.security.keycloak.admin;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * Wires {@link KeycloakAdminClient} only when {@code ipie.security.keycloak.admin.enabled=true} -
 * a separate, explicit gate from merely configuring the other {@code ipie.security.keycloak.admin.*}
 * properties, unlike every other opt-in bean in this platform (e.g. {@link
 * in.gov.ipie.common.security.keycloak.KeycloakTokenAutoConfiguration}, which activates on
 * property presence alone). See {@link KeycloakAdminClient}'s Javadoc for why: this capability
 * needs real Keycloak admin credentials, and a service that depends on {@code common-security}
 * must not gain that capability by accident just because some unrelated property happened to be
 * set.
 */
@AutoConfiguration
@EnableConfigurationProperties(KeycloakAdminProperties.class)
public class KeycloakAdminAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ipie.security.keycloak.admin", name = "enabled", havingValue = "true")
    public KeycloakAdminClient keycloakAdminClient(RestClient.Builder restClientBuilder, KeycloakAdminProperties properties) {
        return new KeycloakAdminClient(restClientBuilder, properties);
    }
}
