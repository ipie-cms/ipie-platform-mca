package in.gov.ipie.common.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import in.gov.ipie.common.audit.AuditRecorder;
import in.gov.ipie.common.audit.model.AuditEvent;
import in.gov.ipie.common.client.config.InterServiceClientProperties;
import in.gov.ipie.common.client.config.ResilienceRegistries;
import in.gov.ipie.common.client.correlation.CorrelationPropagationInterceptor;
import in.gov.ipie.common.client.exception.RemoteServiceException;
import in.gov.ipie.common.client.request.ServiceRequest;
import in.gov.ipie.common.core.correlation.CorrelationConstants;
import in.gov.ipie.common.resilience.exception.TransientDependencyException;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.RetryRegistry;

/**
 * Proves {@link DefaultInterServiceClient}'s actual HTTP behavior against a real local server
 * (same technique as common-resilience's {@code RestClientTimeoutCustomizerTest}): base-url
 * resolution, header/idempotency-key propagation, and the exception-mapping rule that keeps
 * common-resilience's Retry allowlist intact (5xx/IO -&gt; TransientDependencyException, 4xx -&gt;
 * RemoteServiceException, per Development_Environment_Configuration.md, Section 15).
 */
class DefaultInterServiceClientTest {

    private HttpServer server;
    private InterServiceClient client;
    private FakeAuditRecorder auditRecorder;
    private volatile String lastCorrelationIdHeader;
    private volatile String lastIdempotencyKeyHeader;

    @BeforeEach
    void startServerAndClient() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/echo", exchange -> respond(exchange, 200, "{\"status\":\"ok\"}"));
        server.createContext("/capture", exchange -> {
            lastCorrelationIdHeader = exchange.getRequestHeaders().getFirst(CorrelationConstants.CORRELATION_ID_HEADER);
            lastIdempotencyKeyHeader = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            respond(exchange, 201, "{\"id\":\"new-user\"}");
        });
        server.createContext("/boom-5xx", exchange -> respond(exchange, 503, "downstream unavailable"));
        server.createContext("/boom-4xx", exchange -> respond(exchange, 404, "user not found"));
        server.start();

        InterServiceClientProperties properties = new InterServiceClientProperties();
        properties.setServices(Map.of("user-service", "http://localhost:" + server.getAddress().getPort()));

        RestClient.Builder builder = RestClient.builder().requestInterceptor(new CorrelationPropagationInterceptor());
        ResilienceRegistries resilienceRegistries = new ResilienceRegistries(
                CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults(), BulkheadRegistry.ofDefaults(),
                RateLimiterRegistry.ofDefaults());
        auditRecorder = new FakeAuditRecorder();
        client = new DefaultInterServiceClient(builder, properties, resilienceRegistries, auditRecorder, "calling-service");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        MDC.clear();
    }

    @Test
    void get_resolvesTheConfiguredServiceBaseUrl_andDeserializesTheResponseBody() {
        String body = client.exchange(ServiceRequest.get("user-service", "/echo").build(), String.class);

        assertThat(body).isEqualTo("{\"status\":\"ok\"}");
    }

    @Test
    void get_propagatesTheCurrentCorrelationIdFromMdc() {
        MDC.put(CorrelationConstants.CORRELATION_ID_MDC_KEY, "corr-123");

        client.exchange(ServiceRequest.get("user-service", "/capture").build(), String.class);

        assertThat(lastCorrelationIdHeader).isEqualTo("corr-123");
    }

    @Test
    void get_withNoCorrelationIdInMdc_sendsNoCorrelationHeader() {
        client.exchange(ServiceRequest.get("user-service", "/capture").build(), String.class);

        assertThat(lastCorrelationIdHeader).isNull();
    }

    @Test
    void post_sendsTheIdempotencyKeyHeader() {
        ServiceRequest request = ServiceRequest.post("user-service", "/capture")
                .body("{\"name\":\"a\"}")
                .idempotencyKey("key-abc")
                .build();

        String body = client.exchange(request, String.class);

        assertThat(body).isEqualTo("{\"id\":\"new-user\"}");
        assertThat(lastIdempotencyKeyHeader).isEqualTo("key-abc");
    }

    @Test
    void a5xxResponse_isWrappedAsATransientDependencyException_soTheDefaultRetryAllowlistCatchesIt() {
        assertThatThrownBy(() -> client.exchange(ServiceRequest.get("user-service", "/boom-5xx").build(), String.class))
                .isInstanceOf(TransientDependencyException.class);
    }

    @Test
    void a4xxResponse_isWrappedAsARemoteServiceException_neverRetried() {
        assertThatThrownBy(() -> client.exchange(ServiceRequest.get("user-service", "/boom-4xx").build(), String.class))
                .isInstanceOf(RemoteServiceException.class)
                .satisfies(ex -> {
                    RemoteServiceException remoteServiceException = (RemoteServiceException) ex;
                    assertThat(remoteServiceException.serviceName()).isEqualTo("user-service");
                    assertThat(remoteServiceException.statusCode()).isEqualTo(404);
                    assertThat(remoteServiceException.responseBody()).isEqualTo("user not found");
                });
    }

    @Test
    void exceedingTheRateLimiterConfigurationForATargetService_throwsRequestNotPermitted() {
        RateLimiterConfig tinyLimit = RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofSeconds(10))
                .timeoutDuration(Duration.ZERO)
                .build();
        ResilienceRegistries tightlyLimited = new ResilienceRegistries(
                CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults(), BulkheadRegistry.ofDefaults(),
                RateLimiterRegistry.of(tinyLimit));
        InterServiceClientProperties properties = new InterServiceClientProperties();
        properties.setServices(Map.of("user-service", "http://localhost:" + server.getAddress().getPort()));
        InterServiceClient tightlyLimitedClient = new DefaultInterServiceClient(
                RestClient.builder(), properties, tightlyLimited, new FakeAuditRecorder(), "calling-service");

        tightlyLimitedClient.exchange(ServiceRequest.get("user-service", "/echo").build(), String.class);

        assertThatThrownBy(() -> tightlyLimitedClient.exchange(ServiceRequest.get("user-service", "/echo").build(), String.class))
                .isInstanceOf(RequestNotPermitted.class);
    }

    @Test
    void aSuccessfulCall_recordsAnAuditEventWithCallerTargetAndOutcome() {
        client.exchange(ServiceRequest.get("user-service", "/echo").build(), String.class);

        assertThat(auditRecorder.recorded).hasSize(1);
        AuditEvent event = auditRecorder.recorded.get(0);
        assertThat(event.entityId()).isEqualTo("user-service");
        assertThat(event.actorUserId()).isEqualTo("calling-service");
        assertThat(event.serviceName()).isEqualTo("calling-service");
        assertThat(newValueDetails(event)).containsEntry("outcome", "SUCCESS");
    }

    @Test
    void aFailedCall_stillRecordsAnAuditEventWithTheFailureOutcome() {
        assertThatThrownBy(() -> client.exchange(ServiceRequest.get("user-service", "/boom-4xx").build(), String.class))
                .isInstanceOf(RemoteServiceException.class);

        assertThat(auditRecorder.recorded).hasSize(1);
        assertThat(newValueDetails(auditRecorder.recorded.get(0))).containsEntry("outcome", "FAILURE");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> newValueDetails(AuditEvent event) {
        return (Map<String, Object>) event.newValue();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        // text/plain, not application/json: keeps the success-path assertions independent of
        // whether a JSON message converter (e.g. Jackson) happens to be on this module's own test
        // classpath - StringHttpMessageConverter alone is enough to read the body as String.class.
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static final class FakeAuditRecorder implements AuditRecorder {

        private final List<AuditEvent> recorded = new ArrayList<>();

        @Override
        public void record(AuditEvent event) {
            recorded.add(event);
        }
    }
}
