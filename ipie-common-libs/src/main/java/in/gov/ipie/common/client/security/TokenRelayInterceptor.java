package in.gov.ipie.common.client.security;

import java.io.IOException;
import java.time.Instant;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import in.gov.ipie.common.security.keycloak.KeycloakTokenClient;
import in.gov.ipie.common.security.keycloak.RefreshTokenContextHolder;

/**
 * Opt-in alternative to {@link OAuth2ClientCredentialsInterceptor}: forwards the current inbound
 * caller's own validated JWT (populated by {@code common-security}'s
 * {@code ResourceServerAutoConfiguration}) unchanged onto the outbound call, so the downstream
 * service authorizes against the *original user's* permissions rather than this service's
 * identity. Only enable via {@code ipie.client.security.mode=TOKEN_RELAY} for calls that
 * specifically need that - it exposes the caller's token to the downstream service, and there is
 * no inbound authentication to relay outside of a request handled by that filter chain (e.g. a
 * scheduled job), in which case no {@code Authorization} header is set at all.
 *
 * <p><b>Refreshing an expired relayed token.</b> If the inbound JWT has already expired by the
 * time this outbound call is made, forwarding it verbatim is pointless - the downstream service
 * would reject it too. When {@code keycloakTokenClient} is available (see {@code
 * KeycloakTokenAutoConfiguration}, itself opt-in on {@code ipie.security.keycloak.token-uri}) and
 * the original caller supplied a refresh token (see {@code RefreshTokenCaptureFilter} - itself
 * opt-in, and a real security trade-off, per its Javadoc), this mints a fresh access token via
 * {@link KeycloakTokenClient#refreshAccessToken(String)} and relays that instead. Without either
 * piece configured, an expired token is still forwarded unchanged (today's behavior, unaffected).
 */
public class TokenRelayInterceptor implements ClientHttpRequestInterceptor {

    private final KeycloakTokenClient keycloakTokenClient;

    /** No refresh capability - always forwards the current token as-is, even if expired. */
    public TokenRelayInterceptor() {
        this(null);
    }

    /** @param keycloakTokenClient may be {@code null} if refresh-on-expiry is not configured. */
    public TokenRelayInterceptor(KeycloakTokenClient keycloakTokenClient) {
        this.keycloakTokenClient = keycloakTokenClient;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            request.getHeaders().setBearerAuth(currentOrRefreshedTokenValue(jwtAuthentication.getToken()));
        }
        return execution.execute(request, body);
    }

    private String currentOrRefreshedTokenValue(Jwt jwt) {
        if (keycloakTokenClient == null || !isExpired(jwt)) {
            return jwt.getTokenValue();
        }
        String refreshToken = RefreshTokenContextHolder.get();
        if (refreshToken == null) {
            return jwt.getTokenValue();
        }
        return keycloakTokenClient.refreshAccessToken(refreshToken).accessToken();
    }

    private static boolean isExpired(Jwt jwt) {
        Instant expiresAt = jwt.getExpiresAt();
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
