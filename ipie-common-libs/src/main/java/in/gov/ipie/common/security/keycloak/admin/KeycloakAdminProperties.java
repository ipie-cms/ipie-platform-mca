package in.gov.ipie.common.security.keycloak.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code ipie.security.keycloak.admin.*} - credentials and target realm for
 * {@link KeycloakAdminClient}. See that class's Javadoc for why {@link #isEnabled()} is a
 * separate, explicit gate rather than simply reacting to these being configured: this capability
 * requires real Keycloak admin credentials and must not be active anywhere by accident.
 */
@ConfigurationProperties(prefix = "ipie.security.keycloak.admin")
public class KeycloakAdminProperties {

    /** Off by default - see {@link KeycloakAdminClient}'s Javadoc before setting this to {@code true} anywhere. */
    private boolean enabled = false;

    /** e.g. {@code http://keycloak:8080} - no path suffix, both the token and admin REST APIs are derived from it. */
    private String baseUrl;

    /** The realm new clients are created in, e.g. {@code ipie}. */
    private String realm;

    /** The realm the admin credentials below are validated against - almost always {@code master}. */
    private String adminRealm = "master";

    /** Keycloak's built-in admin CLI client, used for the password grant below - rarely needs changing. */
    private String adminClientId = "admin-cli";

    private String adminUsername;

    private String adminPassword;

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

    public String getAdminRealm() {
        return adminRealm;
    }

    public void setAdminRealm(String adminRealm) {
        this.adminRealm = adminRealm;
    }

    public String getAdminClientId() {
        return adminClientId;
    }

    public void setAdminClientId(String adminClientId) {
        this.adminClientId = adminClientId;
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public void setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername;
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }
}
