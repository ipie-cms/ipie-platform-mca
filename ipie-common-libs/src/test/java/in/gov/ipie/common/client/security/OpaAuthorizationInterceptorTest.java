package in.gov.ipie.common.client.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import in.gov.ipie.common.client.config.OpaAuthorizationProperties;

/**
 * Proves {@link OpaAuthorizationInterceptor} against a real (stubbed) OPA server: an allowing
 * decision proceeds to the real execution, a denying decision throws {@link AccessDeniedException}
 * without ever calling execution, and an unreachable OPA instance fails closed by default (denies)
 * - or fails open when {@code failClosed=false} is explicitly set.
 */
class OpaAuthorizationInterceptorTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void anAllowingDecision_proceedsToTheRealExecution() throws Exception {
        startStubOpa(true);
        OpaAuthorizationInterceptor interceptor = interceptor(properties(true));
        TestHttpRequest request = new TestHttpRequest("http://audit-service:8080/api/v1/audit");
        boolean[] executed = {false};

        interceptor.intercept(request, new byte[0], (req, body) -> {
            executed[0] = true;
            return null;
        });

        assertThat(executed[0]).isTrue();
    }

    @Test
    void aDenyingDecision_throwsAccessDeniedExceptionWithoutExecutingTheCall() throws Exception {
        startStubOpa(false);
        OpaAuthorizationInterceptor interceptor = interceptor(properties(true));
        TestHttpRequest request = new TestHttpRequest("http://iam-service:8080/api/v1/admin/reset");
        boolean[] executed = {false};

        assertThatThrownBy(() -> interceptor.intercept(request, new byte[0], (req, body) -> {
            executed[0] = true;
            return null;
        })).isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("calling-service")
                .hasMessageContaining("iam-service");

        assertThat(executed[0]).isFalse();
    }

    @Test
    void anUnreachableOpaInstance_failsClosedByDefault() throws Exception {
        // No server started at all - properties point at a port nothing listens on.
        OpaAuthorizationProperties properties = new OpaAuthorizationProperties();
        properties.setEnabled(true);
        properties.setUrl("http://localhost:1");
        OpaAuthorizationInterceptor interceptor = interceptor(properties);
        TestHttpRequest request = new TestHttpRequest("http://audit-service:8080/api/v1/audit");

        assertThatThrownBy(() -> interceptor.intercept(request, new byte[0], (req, body) -> null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anUnreachableOpaInstance_failsOpenWhenConfigured() throws Exception {
        OpaAuthorizationProperties properties = new OpaAuthorizationProperties();
        properties.setEnabled(true);
        properties.setUrl("http://localhost:1");
        properties.setFailClosed(false);
        OpaAuthorizationInterceptor interceptor = interceptor(properties);
        TestHttpRequest request = new TestHttpRequest("http://audit-service:8080/api/v1/audit");
        boolean[] executed = {false};

        interceptor.intercept(request, new byte[0], (req, body) -> {
            executed[0] = true;
            return null;
        });

        assertThat(executed[0]).isTrue();
    }

    private void startStubOpa(boolean allow) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/data/ipie/interservice/allow", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, "{\"result\":" + allow + "}");
        });
        server.start();
    }

    private OpaAuthorizationProperties properties(boolean enabled) {
        OpaAuthorizationProperties properties = new OpaAuthorizationProperties();
        properties.setEnabled(enabled);
        properties.setUrl("http://localhost:" + server.getAddress().getPort());
        return properties;
    }

    private static OpaAuthorizationInterceptor interceptor(OpaAuthorizationProperties properties) {
        return new OpaAuthorizationInterceptor(RestClient.builder(), properties, "calling-service");
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static final class TestHttpRequest implements HttpRequest {

        private final URI uri;
        private final HttpHeaders headers = new HttpHeaders();

        private TestHttpRequest(String uri) {
            this.uri = URI.create(uri);
        }

        @Override
        public HttpMethod getMethod() {
            return HttpMethod.GET;
        }

        @Override
        public URI getURI() {
            return uri;
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
