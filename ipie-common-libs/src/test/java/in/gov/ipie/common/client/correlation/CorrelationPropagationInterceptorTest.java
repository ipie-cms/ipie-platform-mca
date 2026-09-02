package in.gov.ipie.common.client.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;

import in.gov.ipie.common.core.correlation.CorrelationConstants;

/**
 * Proves the correlation id captured in MDC for the current (inbound) request is carried onto
 * the outbound call so the downstream service's own correlation filter picks it up, and that no
 * header is sent when there is nothing in MDC to propagate.
 *
 * <p>Uses a plain lambda for {@code ClientHttpRequestExecution} (a single-method interface)
 * rather than Mockito - no framework needed for a collaborator this simple.
 */
class CorrelationPropagationInterceptorTest {

    private final CorrelationPropagationInterceptor interceptor = new CorrelationPropagationInterceptor();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void propagatesTheCurrentCorrelationIdFromMdcAsAHeader() throws Exception {
        MDC.put(CorrelationConstants.CORRELATION_ID_MDC_KEY, "corr-abc-123");

        TestHttpRequest request = new TestHttpRequest();
        ClientHttpRequestExecution execution = (req, body) -> null;

        interceptor.intercept(request, new byte[0], execution);

        assertThat(request.getHeaders().getFirst(CorrelationConstants.CORRELATION_ID_HEADER)).isEqualTo("corr-abc-123");
    }

    @Test
    void noCorrelationIdInMdc_sendsNoHeader() throws Exception {
        TestHttpRequest request = new TestHttpRequest();
        ClientHttpRequestExecution execution = (req, body) -> null;

        interceptor.intercept(request, new byte[0], execution);

        assertThat(request.getHeaders().getFirst(CorrelationConstants.CORRELATION_ID_HEADER)).isNull();
    }

    private static final class TestHttpRequest implements HttpRequest {

        private final HttpHeaders headers = new HttpHeaders();

        @Override
        public HttpMethod getMethod() {
            return HttpMethod.GET;
        }

        @Override
        public URI getURI() {
            return URI.create("http://claims-service:8080/api/v1/claims/1");
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
