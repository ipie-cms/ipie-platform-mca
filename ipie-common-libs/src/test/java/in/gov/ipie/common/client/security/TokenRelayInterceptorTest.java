package in.gov.ipie.common.client.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;

import in.gov.ipie.common.security.keycloak.KeycloakTokenClient;
import in.gov.ipie.common.security.keycloak.KeycloakTokenProperties;
import in.gov.ipie.common.security.keycloak.KeycloakTokenResponse;
import in.gov.ipie.common.security.keycloak.RefreshTokenContextHolder;

/**
 * Proves the opt-in token-relay interceptor forwards the current inbound caller's own JWT
 * (populated by common-security's resource-server filter chain) as the outbound Bearer header,
 * sends no {@code Authorization} header at all when there is nothing to relay (e.g. a scheduled
 * job with no inbound request), and - when both a {@link KeycloakTokenClient} and a refresh token
 * are available - refreshes an already-expired token before relaying it instead of forwarding one
 * the downstream service would reject anyway.
 *
 * <p>Uses a plain lambda for {@code ClientHttpRequestExecution} (a single-method interface)
 * rather than Mockito - no framework needed for a collaborator this simple. {@link
 * KeycloakTokenClient} is not an interface, so its refresh tests use a small anonymous subclass
 * overriding {@code refreshAccessToken} instead - the superclass's own {@code RestClient} is never
 * exercised.
 */
class TokenRelayInterceptorTest {

    private final TokenRelayInterceptor interceptor = new TokenRelayInterceptor();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        RefreshTokenContextHolder.clear();
    }

    @Test
    void relaysTheInboundJwtAsTheOutboundBearerHeader() throws Exception {
        Jwt jwt = Jwt.withTokenValue("inbound-user-token")
                .header("alg", "none")
                .claim("sub", "testuser")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        TestHttpRequest request = new TestHttpRequest();
        ClientHttpRequestExecution execution = (req, body) -> null;

        interceptor.intercept(request, new byte[0], execution);

        assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer inbound-user-token");
    }

    @Test
    void noInboundAuthentication_sendsNoAuthorizationHeader() throws Exception {
        TestHttpRequest request = new TestHttpRequest();
        ClientHttpRequestExecution execution = (req, body) -> null;

        interceptor.intercept(request, new byte[0], execution);

        assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
    }

    @Test
    void expiredJwt_noKeycloakTokenClientConfigured_stillForwardsTheExpiredTokenUnchanged() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(expiredJwt()));

        TestHttpRequest request = new TestHttpRequest();
        interceptor.intercept(request, new byte[0], (req, body) -> null);

        assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer expired-inbound-token");
    }

    @Test
    void expiredJwt_keycloakTokenClientConfiguredButNoRefreshTokenAvailable_stillForwardsTheExpiredTokenUnchanged()
            throws Exception {
        TokenRelayInterceptor interceptorWithRefreshCapability = new TokenRelayInterceptor(fakeKeycloakTokenClient());
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(expiredJwt()));

        TestHttpRequest request = new TestHttpRequest();
        interceptorWithRefreshCapability.intercept(request, new byte[0], (req, body) -> null);

        assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer expired-inbound-token");
    }

    @Test
    void expiredJwt_withARefreshTokenAvailable_relaysTheFreshlyRefreshedAccessTokenInstead() throws Exception {
        TokenRelayInterceptor interceptorWithRefreshCapability = new TokenRelayInterceptor(fakeKeycloakTokenClient());
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(expiredJwt()));
        RefreshTokenContextHolder.set("caller-supplied-refresh-token");

        TestHttpRequest request = new TestHttpRequest();
        interceptorWithRefreshCapability.intercept(request, new byte[0], (req, body) -> null);

        assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer refreshed-access-token");
    }

    @Test
    void notYetExpiredJwt_neverAttemptsARefreshEvenWithATokenClientAndRefreshTokenAvailable() throws Exception {
        TokenRelayInterceptor interceptorWithRefreshCapability = new TokenRelayInterceptor(fakeKeycloakTokenClient());
        Jwt notExpired = Jwt.withTokenValue("still-valid-inbound-token")
                .header("alg", "none")
                .claim("sub", "testuser")
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(notExpired));
        RefreshTokenContextHolder.set("caller-supplied-refresh-token");

        TestHttpRequest request = new TestHttpRequest();
        interceptorWithRefreshCapability.intercept(request, new byte[0], (req, body) -> null);

        assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer still-valid-inbound-token");
    }

    private static Jwt expiredJwt() {
        return Jwt.withTokenValue("expired-inbound-token")
                .header("alg", "none")
                .claim("sub", "testuser")
                .expiresAt(Instant.now().minusSeconds(60))
                .build();
    }

    private static KeycloakTokenClient fakeKeycloakTokenClient() {
        return new KeycloakTokenClient(RestClient.builder(), new KeycloakTokenProperties()) {
            @Override
            public KeycloakTokenResponse refreshAccessToken(String refreshToken) {
                return new KeycloakTokenResponse("refreshed-access-token", "new-refresh-token", 300, "Bearer");
            }
        };
    }

    private static final class TestHttpRequest implements HttpRequest {

        private final HttpHeaders headers = new HttpHeaders();

        @Override
        public HttpMethod getMethod() {
            return HttpMethod.GET;
        }

        @Override
        public URI getURI() {
            return URI.create("http://claims-service:8080/api/v1/claims/1");
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return Map.of();
        }
    }
}
