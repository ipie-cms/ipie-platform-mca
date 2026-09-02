package in.gov.ipie.common.security.keycloak.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code ipie.security.keycloak.user-management.*} - target realm and the {@code
 * spring.security.oauth2.client.registration.*} id for {@link KeycloakUserManagementClient}.
 *
 * <p>Unlike {@link KeycloakAdminProperties} (master-realm admin credentials), this capability
 * authenticates as the calling service's own Keycloak client via the client-credentials grant -
 * see {@link KeycloakUserManagementClient}'s Javadoc. Still gated behind {@link #isEnabled()} so
 * depending on {@code common-security} never grants it by accident.
 */
@ConfigurationProperties(prefix = "ipie.security.keycloak.user-management")
public class KeycloakUserManagementProperties {

    /** Off by default - a service must opt in explicitly. */
    private boolean enabled = false;

    /** e.g. {@code http://keycloak:8080} - no path suffix, the admin REST API is derived from it. */
    private String baseUrl;

    /** The realm users/roles are managed in, e.g. {@code ipie}. */
    private String realm;

    /**
     * Matches {@code spring.security.oauth2.client.registration.<registrationId>.*} - the
     * client-credentials-grant registration whose service account was granted the
     * {@code realm-management} client roles this capability needs ({@code manage-users}, and
     * {@code manage-realm} if role definitions are also managed - see deploy/keycloak/realm-export.json).
     */
    private String registrationId = "keycloak-admin";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getRealm() {
        return realm;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }

    public String getRegistrationId() {
        return registrationId;
    }

    public void setRegistrationId(String registrationId) {
        this.registrationId = registrationId;
    }
}
