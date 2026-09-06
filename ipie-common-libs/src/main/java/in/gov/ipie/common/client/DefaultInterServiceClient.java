package in.gov.ipie.common.client;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import in.gov.ipie.common.audit.AuditRecorder;
import in.gov.ipie.common.audit.model.AuditEvent;
import in.gov.ipie.common.audit.model.AuditEventType;
import in.gov.ipie.common.client.config.InterServiceClientProperties;
import in.gov.ipie.common.client.config.ResilienceRegistries;
import in.gov.ipie.common.client.exception.RemoteServiceException;
import in.gov.ipie.common.client.request.ServiceRequest;
import in.gov.ipie.common.core.correlation.CorrelationConstants;
import in.gov.ipie.common.resilience.exception.TransientDependencyException;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.retry.Retry;

/**
 * Default {@link InterServiceClient}: resolves the target service's base URL via
 * {@link InterServiceClientProperties}, executes through a {@link RestClient} already carrying
 * common-resilience's connect/response timeout and this module's security/correlation
 * interceptors (wired in {@code InterServiceClientAutoConfiguration}), and decorates every call
 * with a Retry/CircuitBreaker/Bulkhead/RateLimiter instance named after the target service - so
 * each downstream dependency gets isolated resilience state (one failing service's circuit
 * breaker tripping never affects calls to a different, healthy one) while still inheriting
 * {@code common-resilience}'s shared "default" config the same way {@code @Retry(name = "...")}
 * does. The rate limiter is self-throttling (contains this service's own outbound load against
 * one target), never retried on rejection - see {@code ipie-resilience-defaults.yml}'s
 * {@code ratelimiter.configs.default} comment for why retrying a throttled call defeats the point.
 *
 * <p>Exception mapping keeps the platform's Retry rule intact
 * (Development_Environment_Configuration.md, Section 15: only transient failures are retried,
 * never a functional/business error): connection/read failures and {@code 5xx} responses become
 * {@link TransientDependencyException} - already in {@code common-resilience}'s default
 * retry/circuit-breaker allowlist - while {@code 4xx} responses become {@link RemoteServiceException},
 * which is deliberately not in that allowlist. A tripped circuit breaker surfaces its own
 * {@code CallNotPermittedException} unwrapped, and a full bulkhead its own
 * {@code BulkheadFullException} - this class never fabricates a fallback response; any
 * business-valid fallback (a cached last-known-good value, a clearly-flagged degraded state) is a
 * call-site decision (Development_Environment_Configuration.md, Section 15, "Fallback").
 *
 * <p><b>Every call is recorded through {@link AuditRecorder}</b> - one record per logical call
 * (after retries/circuit-breaker/rate-limiter have already run their course), capturing caller
 * identity, target service, method/path, correlation id, outcome and duration - "every
 * inter-service call logged with caller identity, target, action, timestamp, correlation ID"
 * (Development_Environment_Configuration.md, Section 15). A failure to record the audit event
 * itself never fails the underlying call - the same "audit trouble must not break the business
 * operation" rule {@code AuditAspect} already follows.
 */
public class DefaultInterServiceClient implements InterServiceClient {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultInterServiceClient.class);
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String AUDIT_ACTION = "INTER_SERVICE_CALL";

    private final RestClient restClient;
    private final InterServiceClientProperties properties;
    private final ResilienceRegistries resilienceRegistries;
    private final AuditRecorder auditRecorder;
    private final String callingServiceName;

    public DefaultInterServiceClient(
            RestClient.Builder restClientBuilder,
            InterServiceClientProperties properties,
            ResilienceRegistries resilienceRegistries,
            AuditRecorder auditRecorder,
            String callingServiceName) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
        this.resilienceRegistries = resilienceRegistries;
        this.auditRecorder = auditRecorder;
        this.callingServiceName = callingServiceName;
    }

    @Override
    public <RES> RES exchange(ServiceRequest request, Class<RES> responseType) {
        return audited(request, () -> decorate(request, () -> doExchange(request, responseType, null)));
    }

    @Override
    public <RES> RES exchange(ServiceRequest request, ParameterizedTypeReference<RES> responseType) {
        return audited(request, () -> decorate(request, () -> doExchange(request, null, responseType)));
    }

    @Override
    public void execute(ServiceRequest request) {
        audited(request, () -> decorate(request, () -> {
            doExecute(request);
            return null;
        }));
    }

    private <RES> RES audited(ServiceRequest request, Supplier<RES> call) {
        Instant start = Instant.now();
        try {
            RES result = call.get();
            recordAudit(request, "SUCCESS", null, start);
            return result;
        } catch (RuntimeException e) {
            recordAudit(request, "FAILURE", e.getClass().getSimpleName(), start);
            throw e;
        }
    }

    private void recordAudit(ServiceRequest request, String outcome, String failureReason, Instant start) {
        try {
            Map<String, Object> details = Map.of(
                    "method", request.method().name(),
                    "path", request.path(),
                    "outcome", outcome,
                    "durationMs", Duration.between(start, Instant.now()).toMillis(),
                    "failureReason", failureReason == null ? "" : failureReason);
            AuditEvent event = new AuditEvent(
                    AuditEventType.SECURITY,
                    AUDIT_ACTION,
                    AUDIT_ACTION,
                    request.serviceName(),
                    null,
                    callingServiceName,
                    null,
                    callingServiceName,
                    null,
                    null,
                    details,
                    MDC.get(CorrelationConstants.CORRELATION_ID_MDC_KEY),
                    Instant.now());
            auditRecorder.record(event);
        } catch (RuntimeException auditFailure) {
            LOG.warn("Failed to record inter-service-call audit event for target '{}'", request.serviceName(), auditFailure);
        }
    }

    private <RES> RES decorate(ServiceRequest request, Supplier<RES> call) {
        String instanceName = request.serviceName();
        // Innermost-to-outermost: Bulkhead, then RateLimiter, then CircuitBreaker, then Retry.
        // RateLimiter sits inside CircuitBreaker so an open circuit never even consumes a rate
        // permit; Bulkhead sits innermost so a throttled call never occupies a concurrency slot.
        Supplier<RES> withBulkhead =
                Bulkhead.decorateSupplier(resilienceRegistries.bulkheadRegistry().bulkhead(instanceName), call);
        Supplier<RES> withRateLimiter = RateLimiter.decorateSupplier(
                resilienceRegistries.rateLimiterRegistry().rateLimiter(instanceName), withBulkhead);
        Supplier<RES> withCircuitBreaker = CircuitBreaker.decorateSupplier(
                resilienceRegistries.circuitBreakerRegistry().circuitBreaker(instanceName), withRateLimiter);
        Supplier<RES> withRetry =
                Retry.decorateSupplier(resilienceRegistries.retryRegistry().retry(instanceName), withCircuitBreaker);
        return withRetry.get();
    }

    private <RES> RES doExchange(ServiceRequest request, Class<RES> responseType, ParameterizedTypeReference<RES> typeReference) {
        RestClient.RequestBodySpec spec = buildRequestSpec(request);
        return mapExceptions(request, () -> {
            RestClient.ResponseSpec responseSpec = spec.retrieve();
            return typeReference != null ? responseSpec.body(typeReference) : responseSpec.body(responseType);
        });
    }

    /**
     * Discards the response body via {@code toBodilessEntity()} rather than converting it as
     * {@code Void.class} - {@code RestClient}'s default message converters have no entry for
     * {@code Void}, so requesting one throws {@code UnknownContentTypeException} even on an
     * otherwise-successful response.
     */
    private void doExecute(ServiceRequest request) {
        RestClient.RequestBodySpec spec = buildRequestSpec(request);
        mapExceptions(request, () -> {
            spec.retrieve().toBodilessEntity();
            return null;
        });
    }

    private RestClient.RequestBodySpec buildRequestSpec(ServiceRequest request) {
        URI uri = buildUri(request);
        RestClient.RequestBodySpec spec = restClient.method(request.method())
                .uri(uri)
                .headers(httpHeaders -> {
                    request.headers().forEach(httpHeaders::add);
                    if (request.idempotencyKey() != null) {
                        httpHeaders.add(IDEMPOTENCY_KEY_HEADER, request.idempotencyKey());
                    }
                });
        if (request.body() != null) {
            spec = spec.body(request.body());
        }
        return spec;
    }

    private <RES> RES mapExceptions(ServiceRequest request, Supplier<RES> call) {
        try {
            return call.get();
        } catch (ResourceAccessException e) {
            throw new TransientDependencyException(
                    "Failed to connect to or timed out waiting for service '" + request.serviceName() + "'", e);
        } catch (HttpServerErrorException e) {
            throw new TransientDependencyException(
                    "Service '" + request.serviceName() + "' returned " + e.getStatusCode(), e);
        } catch (HttpClientErrorException e) {
            throw new RemoteServiceException(
                    request.serviceName(), e.getStatusCode().value(), e.getResponseBodyAsString(), e);
        }
    }

    private URI buildUri(ServiceRequest request) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(properties.resolveBaseUrl(request.serviceName()))
                .path(request.path());
        request.queryParams().forEach(builder::queryParam);
        // .encode() here, not on the builder up front - path()/queryParam() above take raw values
        // (a caller-supplied query value with a space or "&" must not be treated as a URI
        // delimiter), so components are percent-encoded once, after assembly.
        return builder.build().encode().toUri();
    }
}
