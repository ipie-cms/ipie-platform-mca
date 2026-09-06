package in.gov.ipie.common.security.keycloak.admin;

/** A Keycloak admin API call (authenticating as the admin user, or creating/reading a client) failed. */
public class KeycloakAdminException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public KeycloakAdminException(String message) {
        super(message);
    }

    public KeycloakAdminException(String message, Throwable cause) {
        super(message, cause);
    }
}
