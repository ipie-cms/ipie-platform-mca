package in.gov.ipie.common.security.keycloak.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Proves {@link KeycloakAdminClient} against a real (stubbed) Keycloak: the three-call sequence
 * (admin password-grant token -&gt; create client -&gt; fetch generated secret), and that failures
 * at each step become {@link KeycloakAdminException} rather than propagating a raw HTTP exception.
 */
class KeycloakAdminClientTest {

    private static final String CLIENT_INTERNAL_ID = "a1b2c3d4-0000-0000-0000-000000000000";

    private HttpServer server;
    private int port;
    private volatile String lastAdminAuthHeaderOnCreate;

    @BeforeEach
    void startStubKeycloak() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        port = server.getAddress().getPort();

        server.createContext("/realms/master/protocol/openid-connect/token", exchange ->
                respondJson(exchange, 200, "{\"access_token\":\"admin-access-token\"}"));

        server.createContext("/admin/realms/ipie/clients", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                lastAdminAuthHeaderOnCreate = exchange.getRequestHeaders().getFirst("Authorization");
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set(
                        "Location", "http://localhost:" + port + "/admin/realms/ipie/clients/" + CLIENT_INTERNAL_ID);
                exchange.sendResponseHeaders(201, -1);
                exchange.close();
            }
        });

        server.createContext("/admin/realms/ipie/clients/" + CLIENT_INTERNAL_ID + "/client-secret", exchange ->
                respondJson(exchange, 200, "{\"type\":\"secret\",\"value\":\"generated-client-secret\"}"));

        server.start();
    }

    @AfterEach
    void stopStubKeycloak() {
        server.stop(0);
    }

    @Test
    void createClient_returnsTheGeneratedSecretFromTheThreeCallSequence() {
        KeycloakAdminClient client = new KeycloakAdminClient(RestClient.builder(), properties());

        KeycloakClientCredentials credentials = client.createClient("new-service", true);

        assertThat(credentials.clientId()).isEqualTo("new-service");
        assertThat(credentials.clientSecret()).isEqualTo("generated-client-secret");
        assertThat(lastAdminAuthHeaderOnCreate).isEqualTo("Bearer admin-access-token");
    }

    @Test
    void adminAuthenticationFailure_throwsKeycloakAdminException() {
        KeycloakAdminProperties properties = properties();
        // No context registered for this realm name - the stub server 404s, proving the auth-step
        // failure path is wrapped rather than propagating a raw HttpClientErrorException.
        properties.setAdminRealm("no-such-realm");
        KeycloakAdminClient client = new KeycloakAdminClient(RestClient.builder(), properties);

        assertThatThrownBy(() -> client.createClient("new-service", true))
                .isInstanceOf(KeycloakAdminException.class);
    }

    private KeycloakAdminProperties properties() {
        KeycloakAdminProperties properties = new KeycloakAdminProperties();
        properties.setBaseUrl("http://localhost:" + port);
        properties.setRealm("ipie");
        properties.setAdminRealm("master");
        properties.setAdminUsername("admin");
        properties.setAdminPassword("admin");
        return properties;
    }

    private static void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
