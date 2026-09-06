package in.gov.ipie.common.client.security;

import java.io.IOException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import in.gov.ipie.common.client.config.OpaAuthorizationProperties;

/**
 * Policy-enforcement point for service-to-service calls: before an outbound call is made, asks a
 * self-hosted Open Policy Agent instance whether {@code callingServiceName} is allowed to call
 * {@code target} at all - "the Notification service may call the IAM service's read endpoints,
 * but not IAM's admin endpoints" is a policy decision this makes explicit and centrally auditable,
 * rather than left implicit in whichever services happen to be network-reachable from each other.
 * Extends the platform's existing OPA-based ABAC pattern (used for user-facing permission checks)
 * to inter-service authorization, per Development_Environment_Configuration.md, Section 15:
 * "extend [OPA] to service-to-service authorization policies, not just user-facing ABAC/RBAC."
 *
 * <p>Queries OPA's Data API ({@code POST {url}{policyPath}} with
 * {@code {"input": {"caller":..., "target":..., "method":..., "path":...}}}) and expects a
 * {@code {"result": true|false}} response - the standard shape for a boolean Rego rule. See
 * {@code deploy/opa/policies/interservice.rego} for a sample policy matching that input/output
 * shape.
 *
 * <p>An unreachable or erroring OPA instance is treated as a denial by default
 * ({@link OpaAuthorizationProperties#isFailClosed()}) - the same "an unavailable security control
 * must never silently degrade into allow-everything" reasoning
 * {@code common-file-storage}'s {@code FailClosedVirusScanner} already establishes for virus
 * scanning in this platform.
 */
public class OpaAuthorizationInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(OpaAuthorizationInterceptor.class);

    private final RestClient restClient;
    private final OpaAuthorizationProperties properties;
    private final String callingServiceName;

    public OpaAuthorizationInterceptor(
            RestClient.Builder restClientBuilder, OpaAuthorizationProperties properties, String callingServiceName) {
        this.restClient = restClientBuilder.baseUrl(properties.getUrl()).build();
        this.properties = properties;
        this.callingServiceName = callingServiceName;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String targetServiceName = targetServiceNameFrom(request);
        if (!isAllowed(targetServiceName, request)) {
            throw new AccessDeniedException("OPA denied the call from '" + callingServiceName + "' to '"
                    + targetServiceName + "' " + request.getMethod() + " " + request.getURI().getPath());
        }
        return execution.execute(request, body);
    }

    private boolean isAllowed(String targetServiceName, HttpRequest request) {
        Map<String, Object> input = Map.of("input", Map.of(
                "caller", callingServiceName,
                "target", targetServiceName,
                "method", request.getMethod().name(),
                "path", request.getURI().getPath()));

        try {
            OpaDecisionResponse response =
                    restClient.post().uri(properties.getPolicyPath()).body(input).retrieve().body(OpaDecisionResponse.class);
            return response != null && Boolean.TRUE.equals(response.result());
        } catch (RestClientException e) {
            LOG.warn("Could not reach OPA to authorize call from '{}' to '{}' - {} (failClosed={})",
                    callingServiceName, targetServiceName, e.getMessage(), properties.isFailClosed());
            return !properties.isFailClosed();
        }
    }

    /**
     * The target service name is not otherwise available to a {@link ClientHttpRequestInterceptor}
     * (it only sees the already-resolved {@link HttpRequest}, whose host is the target's resolved
     * base URL, not necessarily its logical service name) - {@code DefaultInterServiceClient}
     * resolves the base URL from {@code ServiceRequest.serviceName()} via
     * {@code InterServiceClientProperties}, so the reverse mapping would require carrying the
     * logical name through separately. Using the request's host is close enough for the common
     * case (the default {@code base-url-pattern} of {@code http://{service}:8080} makes the host
     * literally equal to the service name); a deployment overriding a target to a differently-named
     * host should account for that when writing its Rego policy.
     */
    private static String targetServiceNameFrom(HttpRequest request) {
        return request.getURI().getHost();
    }

    private record OpaDecisionResponse(Boolean result) {
    }
}
