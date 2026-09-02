package in.gov.ipie.common.resilience.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

/**
 * Proves the "Timeout" control (Development_Environment_Configuration.md, Section 15) is real:
 * a service's auto-configured {@code RestClient.Builder}, customized by
 * {@link IpieResilienceAutoConfiguration.RestClientTimeoutConfiguration}, actually aborts a
 * blocking call once the configured response timeout elapses - not just a
 * {@code TimeLimiter}, which only bounds asynchronous calls.
 */
class RestClientTimeoutCustomizerTest {

    private HttpServer server;

    @BeforeEach
    void startSlowServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write("late".getBytes());
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void responseTimeoutFromPropertiesAbortsABlockingCallThatOutlivesIt() {
        IpieResilienceHttpProperties properties = new IpieResilienceHttpProperties();
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setResponseTimeout(Duration.ofMillis(200));

        RestClient.Builder builder = RestClient.builder();
        new IpieResilienceAutoConfiguration.RestClientTimeoutConfiguration()
                .ipieHttpTimeoutCustomizer(properties)
                .customize(builder);
        RestClient client = builder.baseUrl("http://localhost:" + server.getAddress().getPort()).build();

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri("/slow").retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // The server sleeps 2s; a 200ms response timeout must cut the call off well before that.
        assertThat(elapsedMillis).isLessThan(1900);
    }
}
