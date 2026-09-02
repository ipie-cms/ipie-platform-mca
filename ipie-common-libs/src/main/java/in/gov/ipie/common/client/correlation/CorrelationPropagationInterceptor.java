package in.gov.ipie.common.client.correlation;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import in.gov.ipie.common.core.correlation.CorrelationConstants;

/**
 * Carries the current request's correlation id onto every outbound inter-service call, so the
 * downstream service's own {@code CorrelationIdFilter} (common-observability) picks it up instead
 * of minting a new one - required platform-wide for cross-service log correlation
 * (Backend_Environment_Configuration.md, Section 9: every log line carries the request's trace
 * ID). Reads {@link CorrelationConstants#CORRELATION_ID_MDC_KEY} directly from MDC rather than
 * depending on {@code common-observability} - the MDC key is itself defined in
 * {@code common-core}, so no extra module dependency is needed just for this.
 */
public class CorrelationPropagationInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String correlationId = MDC.get(CorrelationConstants.CORRELATION_ID_MDC_KEY);
        if (correlationId != null && !correlationId.isBlank()) {
            request.getHeaders().add(CorrelationConstants.CORRELATION_ID_HEADER, correlationId);
        }
        return execution.execute(request, body);
    }
}
