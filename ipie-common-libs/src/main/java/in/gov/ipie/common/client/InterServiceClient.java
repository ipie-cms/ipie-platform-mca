package in.gov.ipie.common.client;

import org.springframework.core.ParameterizedTypeReference;

import in.gov.ipie.common.client.request.ServiceRequest;

/**
 * The one port every service calls another service through. Deliberately generic - a single
 * {@code exchange}/{@code execute} pair covers every HTTP method and every request/response body
 * shape, rather than one bespoke method per target service or per verb; if a specific call needs
 * something this generic shape cannot express, that is a sign that call belongs in a more
 * specific, hand-written client instead of being forced through here.
 *
 * <p>Every call made through an implementation of this port automatically gets: the shared
 * connection/response timeout and per-target-service Retry/CircuitBreaker/Bulkhead behavior from
 * {@code common-resilience}, an {@code Authorization} header from {@code common-security}
 * (Keycloak client-credentials by default, token relay opt-in - see
 * {@code InterServiceSecurityProperties}), and correlation-id propagation - see this module's
 * README for the full behavior and configuration.
 */
public interface InterServiceClient {

    /** Executes {@code request} and deserializes the response body as {@code responseType}. */
    <RES> RES exchange(ServiceRequest request, Class<RES> responseType);

    /** Same as {@link #exchange(ServiceRequest, Class)}, for a generic response type (e.g. a list). */
    <RES> RES exchange(ServiceRequest request, ParameterizedTypeReference<RES> responseType);

    /** Executes {@code request}, discarding any response body (e.g. a {@code DELETE}). */
    void execute(ServiceRequest request);
}
