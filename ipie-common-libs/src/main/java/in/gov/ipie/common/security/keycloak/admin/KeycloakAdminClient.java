package in.gov.ipie.common.security.keycloak.admin;

import java.net.URI;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * Creates a new Keycloak client (an OAuth2 client-id/client-secret pair, e.g. for a brand-new
 * microservice) via Keycloak's Admin REST API - automates the "Register a Keycloak client for the
 * service" step that {@code Backend_Environment_Configuration.md} otherwise documents as a manual
 * edit to {@code deploy/keycloak/realm-export.json}.
 *
 * <p><b>This is a platform-provisioning capability, not a business-microservice capability - keep
 * it that way.</b> {@link KeycloakAdminProperties#isEnabled()} must be explicitly set to
 * {@code true}, and doing so requires configuring real Keycloak <i>admin</i> credentials (a
 * username/password that authenticates against the {@code master} realm, or whichever
 * {@code admin-realm} is configured) somewhere this component can read them. Those credentials
 * can create, modify, or delete <i>any</i> client in the target realm - granting them to a
 * typical business microservice's runtime environment massively widens that service's blast
 * radius if it is ever compromised, for a capability it only needs once, at onboarding. Only
 * enable this in a deliberately narrow, short-lived context - a one-off provisioning
 * script/task, a CI/CD pipeline step, or a dedicated internal platform-admin tool - never as a
 * routinely-configured dependency of a service that also serves regular business traffic.
 */
public class KeycloakAdminClient {

    private final RestClient restClient;
    private final KeycloakAdminProperties properties;

    public KeycloakAdminClient(RestClient.Builder restClientBuilder, KeycloakAdminProperties properties) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
    }

    /**
     * Creates a new confidential client named {@code clientId} in the configured realm and
     * returns its generated secret. {@code serviceAccountsEnabled} controls whether the new
     * client can use the OAuth2 client-credentials grant (see {@code common-client}'s
     * {@code OAuth2ClientCredentialsInterceptor}) - {@code true} for a service that will make
     * outbound inter-service calls, {@code false} for a resource-server-only client (see
     * {@code deploy/keycloak/realm-export.json}'s own clients for both shapes).
     *
     * @throws KeycloakAdminException if admin authentication fails, the client already exists
     *     (Keycloak responds {@code 409 Conflict}), or the client-secret lookup fails
     */
    public KeycloakClientCredentials createClient(String clientId, boolean serviceAccountsEnabled) {
        String adminAccessToken = obtainAdminAccessToken();
        String clientInternalId = createClientAndReturnInternalId(clientId, serviceAccountsEnabled, adminAccessToken);
        String secret = fetchClientSecret(clientInternalId, adminAccessToken);
        return new KeycloakClientCredentials(clientId, secret);
    }

    private String obtainAdminAccessToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", properties.getAdminClientId());
        form.add("username", properties.getAdminUsername());
        form.add("password", properties.getAdminPassword());

        try {
            AdminTokenResponse response = restClient.post()
                    .uri(properties.getBaseUrl() + "/realms/" + properties.getAdminRealm() + "/protocol/openid-connect/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(AdminTokenResponse.class);
            if (response == null) {
                throw new KeycloakAdminException("Keycloak's token endpoint returned an empty response body for the admin password grant");
            }
            return response.accessToken();
        } catch (HttpStatusCodeException e) {
            throw new KeycloakAdminException("Failed to authenticate as the Keycloak admin user (" + e.getStatusCode() + ")", e);
        }
    }

    private String createClientAndReturnInternalId(String clientId, boolean serviceAccountsEnabled, String adminAccessToken) {
        Map<String, Object> newClient = Map.of(
                "clientId", clientId,
                "enabled", true,
                "publicClient", false,
                "clientAuthenticatorType", "client-secret",
                "serviceAccountsEnabled", serviceAccountsEnabled,
                "standardFlowEnabled", false,
                "directAccessGrantsEnabled", false);

        try {
            URI location = restClient.post()
                    .uri(properties.getBaseUrl() + "/admin/realms/" + properties.getRealm() + "/clients")
                    .headers(headers -> headers.setBearerAuth(adminAccessToken))
                    .body(newClient)
                    .retrieve()
                    .toBodilessEntity()
                    .getHeaders()
                    .getLocation();
            if (location == null) {
                throw new KeycloakAdminException(
                        "Keycloak did not return a Location header for the newly created client '" + clientId + "'");
            }
            String path = location.getPath();
            return path.substring(path.lastIndexOf('/') + 1);
        } catch (HttpStatusCodeException e) {
            throw new KeycloakAdminException(
                    "Failed to create Keycloak client '" + clientId + "' (" + e.getStatusCode()
                            + ") - it may already exist", e);
        }
    }

    private String fetchClientSecret(String clientInternalId, String adminAccessToken) {
        try {
            ClientSecretResponse response = restClient.get()
                    .uri(properties.getBaseUrl() + "/admin/realms/" + properties.getRealm() + "/clients/"
                            + clientInternalId + "/client-secret")
                    .headers(headers -> headers.setBearerAuth(adminAccessToken))
                    .retrieve()
                    .body(ClientSecretResponse.class);
            if (response == null) {
                throw new KeycloakAdminException("Keycloak returned an empty response body for the client-secret lookup");
            }
            return response.value();
        } catch (HttpStatusCodeException e) {
            throw new KeycloakAdminException("Failed to read the generated client secret (" + e.getStatusCode() + ")", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AdminTokenResponse(@JsonProperty("access_token") String accessToken) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ClientSecretResponse(String type, String value) {
    }
}
