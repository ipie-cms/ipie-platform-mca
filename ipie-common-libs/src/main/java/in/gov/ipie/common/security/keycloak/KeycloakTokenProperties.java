package in.gov.ipie.common.security.keycloak;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code ipie.security.keycloak.*} - this service's own Keycloak client, used by
 * {@link KeycloakTokenClient} to call the token endpoint directly (refresh-token grant today; see
 * that class's Javadoc). Deliberately separate from {@code spring.security.oauth2.client.registration.*}
 * (used by {@code common-client}'s client-credentials support) - that property tree is owned by
 * Spring Security's own {@code ClientRegistrationRepository} machinery, which this class does not
 * use (a plain, direct {@code RestClient} call to the token endpoint is simpler and sufficient for
 * a single grant type with no caching/refresh-scheduling needs of its own).
 */
@ConfigurationProperties(prefix = "ipie.security.keycloak")
public class KeycloakTokenProperties {

    /** e.g. {@code http://keycloak:8080/realms/ipie/protocol/openid-connect/token}. */
    private String tokenUri;

    /** This service's own Keycloak client id (the same one {@code spring.security.oauth2.resourceserver.jwt.*} validates against). */
    private String clientId;

    /** This service's own Keycloak client secret. Confidential clients only - leave unset for a public client. */
    private String clientSecret;

    public String getTokenUri() {
        return tokenUri;
    }

    public void setTokenUri(String tokenUri) {
        this.tokenUri = tokenUri;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }
}
