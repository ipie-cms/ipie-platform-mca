package in.gov.ipie.common.security.keycloak;

/**
 * Keycloak rejected a refresh-token grant - the refresh token is expired, already used (Keycloak
 * rotates and invalidates the previous one on every refresh), or revoked. Deliberately not an
 * {@code IpieException} subtype: this is a fact about a specific token, not automatically the
 * calling service's own domain error - the same reasoning {@code common-client}'s {@code
 * RemoteServiceException} documents. Catch this at the call site and decide what it means there
 * (e.g. force the original caller to re-authenticate).
 */
public class InvalidRefreshTokenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidRefreshTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
