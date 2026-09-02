package in.gov.ipie.common.security.keycloak;

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
 * Proves {@link KeycloakTokenClient} against a real (stubbed) HTTP server: the refresh-token
 * grant is sent as a form-encoded POST with the expected fields, a successful response is parsed
 * into {@link KeycloakTokenResponse}, and a rejection (expired/reused/revoked refresh token)
 * becomes {@link InvalidRefreshTokenException}.
 */
class KeycloakTokenClientTest {

    private HttpServer server;
    private KeycloakTokenClient client;
    private volatile String lastRequestBody;

    @BeforeEach
    void startStubKeycloak() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/token", exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respondJson(exchange, 200, "{\"access_token\":\"new-access-token\",\"refresh_token\":\"new-refresh-token\","
                    + "\"expires_in\":300,\"token_type\":\"Bearer\"}");
        });
        server.createContext("/token-rejected", exchange -> respondJson(exchange, 400,
                "{\"error\":\"invalid_grant\",\"error_description\":\"Token is not active\"}"));
        server.start();

        KeycloakTokenProperties properties = new KeycloakTokenProperties();
        properties.setTokenUri("http://localhost:" + server.getAddress().getPort() + "/token");
        properties.setClientId("ipie-service-template");
        properties.setClientSecret("ipie-service-template-secret");
        client = new KeycloakTokenClient(RestClient.builder(), properties);
    }

    @AfterEach
    void stopStubKeycloak() {
        server.stop(0);
    }

    @Test
    void refreshAccessToken_returnsTheNewAccessAndRefreshTokens() {
        KeycloakTokenResponse response = client.refreshAccessToken("old-refresh-token");

        assertThat(response.accessToken()).isEqualTo("new-access-token");
        assertThat(response.refreshToken()).isEqualTo("new-refresh-token");
        assertThat(response.expiresInSeconds()).isEqualTo(300);
        assertThat(response.tokenType()).isEqualTo("Bearer");
    }

    @Test
    void refreshAccessToken_sendsTheGrantAsAFormEncodedPostWithClientCredentials() {
        client.refreshAccessToken("old-refresh-token");

        assertThat(lastRequestBody)
                .contains("grant_type=refresh_token")
                .contains("refresh_token=old-refresh-token")
                .contains("client_id=ipie-service-template")
                .contains("client_secret=ipie-service-template-secret");
    }

    @Test
    void aRejectedRefreshToken_throwsInvalidRefreshTokenException() {
        KeycloakTokenProperties rejectingProperties = new KeycloakTokenProperties();
        rejectingProperties.setTokenUri("http://localhost:" + server.getAddress().getPort() + "/token-rejected");
        rejectingProperties.setClientId("ipie-service-template");
        KeycloakTokenClient rejectingClient = new KeycloakTokenClient(RestClient.builder(), rejectingProperties);

        assertThatThrownBy(() -> rejectingClient.refreshAccessToken("expired-refresh-token"))
                .isInstanceOf(InvalidRefreshTokenException.class);
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
