package in.gov.ipie.common.client.security;

import java.io.IOException;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;

/**
 * Default, recommended security for outbound inter-service calls: authenticates this service as
 * itself against Keycloak via the OAuth2 client-credentials grant, using {@code registrationId}
 * ({@code spring.security.oauth2.client.registration.<registrationId>.*}). The downstream service
 * sees this service's own identity, not the original caller's - the correct model for genuine
 * service-to-service calls (see {@code InterServiceSecurityProperties}'s Javadoc for when
 * token-relay is more appropriate instead).
 *
 * <p>{@link OAuth2AuthorizedClientManager#authorize} caches the token and only performs a real
 * token-endpoint round trip when none is cached yet or the cached one is expired - this
 * interceptor does not add a network call on every request.
 *
 * <p>{@code authorizedClientManager} may be {@code null} - a service can depend on {@code
 * common-client} without configuring {@code spring.security.oauth2.client.registration.*} at all
 * (e.g. it hasn't made an outbound call yet, or only ever uses {@code TOKEN_RELAY}/{@code NONE}
 * for the calls it does make). This class deliberately fails only when a
 * {@code CLIENT_CREDENTIALS}-mode call is actually attempted without one configured, not merely
 * because the dependency is present - a null manager here must never fail the whole application's
 * startup (see {@code InterServiceClientAutoConfiguration}'s Javadoc).
 */
public class OAuth2ClientCredentialsInterceptor implements ClientHttpRequestInterceptor {

    private final OAuth2AuthorizedClientManager authorizedClientManager;
    private final String registrationId;

    public OAuth2ClientCredentialsInterceptor(OAuth2AuthorizedClientManager authorizedClientManager, String registrationId) {
        this.authorizedClientManager = authorizedClientManager;
        this.registrationId = registrationId;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (authorizedClientManager == null) {
            throw new IllegalStateException(
                    "ipie.client.security.mode=CLIENT_CREDENTIALS (the default) requires "
                            + "spring.security.oauth2.client.registration." + registrationId
                            + ".* to be configured - see common-client's README, or set "
                            + "ipie.client.security.mode=NONE/TOKEN_RELAY if that is genuinely not needed");
        }
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest.withClientRegistrationId(registrationId)
                .principal(registrationId)
                .build();
        OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(authorizeRequest);
        if (authorizedClient == null) {
            throw new IllegalStateException(
                    "Unable to obtain a client-credentials access token for registration '" + registrationId
                            + "' - check spring.security.oauth2.client.registration." + registrationId
                            + ".* and that this service's Keycloak client has serviceAccountsEnabled=true");
        }
        request.getHeaders().setBearerAuth(authorizedClient.getAccessToken().getTokenValue());
        return execution.execute(request, body);
    }
}
