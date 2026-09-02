package in.gov.ipie.common.client.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

/**
 * Proves the client-credentials interceptor attaches the access token from
 * {@link OAuth2AuthorizedClientManager} as a Bearer header, and fails loudly - not silently - when
 * no token can be obtained (e.g. the registration is misconfigured).
 *
 * <p>Uses hand-written fakes for the two collaborating functional interfaces
 * ({@code OAuth2AuthorizedClientManager}, {@code ClientHttpRequestExecution}) rather than Mockito
 * - both are single-method interfaces a lambda expresses directly, with no framework needed.
 */
class OAuth2ClientCredentialsInterceptorTest {

    private static final ClientRegistration CLIENT_REGISTRATION = ClientRegistration.withRegistrationId("ipie-interservice")
            .clientId("ipie-service-template")
            .clientSecret("test-secret")
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .tokenUri("http://keycloak:8080/realms/ipie/protocol/openid-connect/token")
            .build();

    @Test
    void setsTheBearerHeaderFromTheAuthorizedClientsAccessToken() throws Exception {
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, "service-token-value", Instant.now(), Instant.now().plusSeconds(300));
        OAuth2AuthorizedClient authorizedClient = new OAuth2AuthorizedClient(CLIENT_REGISTRATION, "ipie-interservice", accessToken);
        OAuth2AuthorizedClientManager manager = authorizeRequest -> authorizedClient;

        OAuth2ClientCredentialsInterceptor interceptor = new OAuth2ClientCredentialsInterceptor(manager, "ipie-interservice");
        TestHttpRequest request = new TestHttpRequest();
        ClientHttpRequestExecution execution = (req, body) -> null;

        interceptor.intercept(request, new byte[0], execution);

        assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer service-token-value");
    }

    @Test
    void noAuthorizedClientAvailable_failsLoudlyInsteadOfSendingAnUnauthenticatedRequest() {
        OAuth2AuthorizedClientManager manager = authorizeRequest -> null;
        OAuth2ClientCredentialsInterceptor interceptor = new OAuth2ClientCredentialsInterceptor(manager, "ipie-interservice");
        ClientHttpRequestExecution execution = (req, body) -> null;

        assertThatThrownBy(() -> interceptor.intercept(new TestHttpRequest(), new byte[0], execution))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ipie-interservice");
    }

    /**
     * A {@code null} manager means no {@code spring.security.oauth2.client.registration.*} is
     * configured at all - proves the failure surfaces only here, at an actual call attempt, not
     * during bean creation/application startup (see {@code InterServiceClientAutoConfiguration}'s
     * Javadoc: depending on common-client must never, by itself, fail a service's startup).
     */
    @Test
    void noAuthorizedClientManagerConfiguredAtAll_failsLoudlyOnlyWhenACallIsActuallyAttempted() {
        OAuth2ClientCredentialsInterceptor interceptor = new OAuth2ClientCredentialsInterceptor(null, "ipie-interservice");
        ClientHttpRequestExecution execution = (req, body) -> null;

        assertThatThrownBy(() -> interceptor.intercept(new TestHttpRequest(), new byte[0], execution))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLIENT_CREDENTIALS")
                .hasMessageContaining("ipie-interservice");
    }

    private static final class TestHttpRequest implements HttpRequest {

        private final HttpHeaders headers = new HttpHeaders();

        @Override
        public HttpMethod getMethod() {
            return HttpMethod.GET;
        }

        @Override
        public URI getURI() {
            return URI.create("http://user-service:8080/api/v1/users/42");
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
