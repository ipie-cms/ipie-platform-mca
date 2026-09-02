package in.gov.ipie.common.security.keycloak;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Calls Keycloak's token endpoint directly with the {@code refresh_token} grant, to mint a fresh
 * access token from a still-valid refresh token without the original caller having to
 * re-authenticate with a username/password. Typical use: {@code common-client}'s {@code
 * TokenRelayInterceptor} detects the current inbound JWT it would otherwise relay has expired,
 * and - only if a refresh token is actually available (see {@code RefreshTokenContextHolder}) -
 * calls {@link #refreshAccessToken(String)} to get a live one instead of forwarding an expired
 * (and therefore useless) token downstream.
 *
 * <p>Uses this service's own Keycloak client ({@code ipie.security.keycloak.client-id}/{@code
 * client-secret}) - the same client already registered for JWT validation, not a new one -
 * because Keycloak's refresh-token grant is validated against the client that owns the token's
 * session, which for a relayed user token is whichever client the user originally authenticated
 * through (typically the same confidential client this service itself uses; see {@code
 * deploy/keycloak/realm-export.json}).
 *
 * <p><b>Handling the response's rotated refresh token matters.</b> Keycloak rotates the refresh
 * token on every use by default - the caller of {@link #refreshAccessToken(String)} must persist
 * {@link KeycloakTokenResponse#refreshToken()} for the *next* refresh and discard the one it just
 * spent; reusing a spent refresh token fails with {@link InvalidRefreshTokenException}.
 */
public class KeycloakTokenClient {

    private final RestClient restClient;
    private final KeycloakTokenProperties properties;

    public KeycloakTokenClient(RestClient.Builder restClientBuilder, KeycloakTokenProperties properties) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
    }

    /**
     * Exchanges {@code refreshToken} for a fresh access token (and a new refresh token to replace
     * it - see this class's Javadoc).
     *
     * @throws InvalidRefreshTokenException if Keycloak rejects the refresh token (expired,
     *     already used, or revoked) - a {@code 400}/{@code 401} from the token endpoint
     */
    public KeycloakTokenResponse refreshAccessToken(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("client_id", properties.getClientId());
        if (properties.getClientSecret() != null && !properties.getClientSecret().isBlank()) {
            form.add("client_secret", properties.getClientSecret());
        }

        try {
            TokenEndpointResponse response = restClient.post()
                    .uri(properties.getTokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenEndpointResponse.class);
            if (response == null) {
                throw new InvalidRefreshTokenException(
                        "Keycloak's token endpoint returned an empty response body for the refresh_token grant", null);
            }
            return new KeycloakTokenResponse(
                    response.accessToken(), response.refreshToken(), response.expiresIn(), response.tokenType());
        } catch (HttpClientErrorException e) {
            throw new InvalidRefreshTokenException(
                    "Keycloak rejected the refresh token (" + e.getStatusCode() + ") - it may be expired, "
                            + "already used, or revoked", e);
        }
    }

    /** Keycloak's token endpoint JSON response shape - only the fields this class needs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenEndpointResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("token_type") String tokenType) {
    }
}
