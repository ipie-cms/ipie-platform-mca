package in.gov.ipie.common.security.keycloak;

/**
 * A minted/refreshed token pair from Keycloak's token endpoint. {@code refreshToken} is the new
 * refresh token to use for the *next* refresh - Keycloak rotates it on every refresh grant by
 * default, so the one that was just spent must not be reused (see {@link KeycloakTokenClient}'s
 * Javadoc: the caller is responsible for storing this replacement).
 */
public record KeycloakTokenResponse(String accessToken, String refreshToken, long expiresInSeconds, String tokenType) {
}
