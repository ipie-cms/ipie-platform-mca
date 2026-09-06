package in.gov.ipie.common.security.keycloak.admin;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;

/**
 * Wires {@link KeycloakUserManagementClient} only when {@code
 * ipie.security.keycloak.user-management.enabled=true} - same explicit-gate reasoning as {@link
 * KeycloakAdminAutoConfiguration}, even though this capability is far narrower in scope (see
 * {@link KeycloakUserManagementClient}'s Javadoc): a service must still opt in deliberately rather
 * than gain it merely by depending on {@code common-security}.
 *
 * <p>The {@link OAuth2AuthorizedClientManager} bean here is defined the same way {@code
 * common-client}'s {@code InterServiceClientAutoConfiguration} defines its own - both are
 * {@code @ConditionalOnMissingBean}, so whichever module's autoconfiguration runs first supplies
 * the single shared instance when a service depends on both; one manager serves every {@code
 * spring.security.oauth2.client.registration.*} entry regardless of which module needed it.
 */
// after Boot's OAuth2 client autoconfiguration, because the @ConditionalOnBean below asks for a
// ClientRegistrationRepository and that condition only sees beans registered by autoconfigurations
// already processed. Without the ordering the condition silently evaluates false and the manager is
// never created - which stayed invisible while this bean also backed off to Boot's own manager.
@AutoConfiguration(after = org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration.class)
@EnableConfigurationProperties(KeycloakUserManagementProperties.class)
@ConditionalOnProperty(prefix = "ipie.security.keycloak.user-management", name = "enabled", havingValue = "true")
public class KeycloakUserManagementAutoConfiguration {

    /**
     * A client-credentials manager dedicated to this client, deliberately NOT shared with whatever
     * {@code OAuth2AuthorizedClientManager} the application context happens to hold.
     *
     * <p>It used to be {@code @ConditionalOnMissingBean(OAuth2AuthorizedClientManager.class)}, which
     * meant Spring Boot's own {@code DefaultOAuth2AuthorizedClientManager} - registered for any
     * servlet web application - won, and this backed off. That manager resolves the client from the
     * current HTTP request and asserts one exists, so every call here worked only because it
     * happened to be made on a request thread. The moment provisioning moved to a message listener
     * the same code failed with "servletRequest cannot be null", having looked correct for as long
     * as nothing called it off-request.
     *
     * <p>{@link AuthorizedClientServiceOAuth2AuthorizedClientManager} has no such dependency, which
     * is what a client used from schedulers, consumers and request threads alike needs. Named and
     * injected by qualifier so it cannot be displaced again.
     */
    @Bean("keycloakAdminAuthorizedClientManager")
    @ConditionalOnBean(ClientRegistrationRepository.class)
    @ConditionalOnMissingBean(name = "keycloakAdminAuthorizedClientManager")
    public OAuth2AuthorizedClientManager keycloakAdminAuthorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository) {
        OAuth2AuthorizedClientService authorizedClientService =
                new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);
        OAuth2AuthorizedClientProvider authorizedClientProvider =
                OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build();
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrationRepository, authorizedClientService);
        manager.setAuthorizedClientProvider(authorizedClientProvider);
        return manager;
    }

    @Bean
    @ConditionalOnMissingBean
    public KeycloakUserManagementClient keycloakUserManagementClient(
            RestClient.Builder restClientBuilder,
            @Qualifier("keycloakAdminAuthorizedClientManager") OAuth2AuthorizedClientManager authorizedClientManager,
            KeycloakUserManagementProperties properties) {
        return new KeycloakUserManagementClient(restClientBuilder, authorizedClientManager, properties);
    }
}
