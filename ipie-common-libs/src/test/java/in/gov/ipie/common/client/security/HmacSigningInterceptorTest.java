package in.gov.ipie.common.client.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;

import in.gov.ipie.common.security.hmac.HmacSignature;
import in.gov.ipie.common.security.hmac.HmacSignatureVerificationFilter;

/**
 * Proves {@link HmacSigningInterceptor} adds headers that {@code common-security}'s
 * {@code HmacSignatureVerificationFilter} can independently verify - the two never share code
 * beyond {@link HmacSignature}, so this is what actually proves the client and server sides agree.
 */
class HmacSigningInterceptorTest {

    private static final String SECRET = "shared-secret";
    private static final String KEY_ID = "test-key";

    private final HmacSigningInterceptor interceptor = new HmacSigningInterceptor(KEY_ID, SECRET);

    @Test
    void intercept_addsHeadersThatIndependentlyVerifyAgainstHmacSignature() throws Exception {
        byte[] body = "{\"amount\":100}".getBytes(StandardCharsets.UTF_8);
        TestHttpRequest request = new TestHttpRequest(HttpMethod.POST, URI.create("http://claims-service:8080/api/v1/claims"));
        ClientHttpRequestExecution execution = (req, b) -> null;

        interceptor.intercept(request, body, execution);

        HttpHeaders headers = request.getHeaders();
        assertThat(headers.getFirst(HmacSignatureVerificationFilter.KEY_ID_HEADER)).isEqualTo(KEY_ID);
        assertThat(headers.getFirst(HmacSignatureVerificationFilter.NONCE_HEADER)).isNotBlank();
        assertThat(headers.getFirst(HmacSignatureVerificationFilter.TIMESTAMP_HEADER)).isNotBlank();

        boolean valid = HmacSignature.verify(
                SECRET, "POST", "/api/v1/claims",
                headers.getFirst(HmacSignatureVerificationFilter.TIMESTAMP_HEADER),
                headers.getFirst(HmacSignatureVerificationFilter.NONCE_HEADER),
                body,
                headers.getFirst(HmacSignatureVerificationFilter.SIGNATURE_HEADER));
        assertThat(valid).isTrue();
    }

    @Test
    void intercept_usesADifferentNonceOnEachCall() throws Exception {
        TestHttpRequest first = new TestHttpRequest(HttpMethod.GET, URI.create("http://claims-service:8080/api/v1/claims/1"));
        TestHttpRequest second = new TestHttpRequest(HttpMethod.GET, URI.create("http://claims-service:8080/api/v1/claims/1"));
        ClientHttpRequestExecution execution = (req, b) -> null;

        interceptor.intercept(first, new byte[0], execution);
        interceptor.intercept(second, new byte[0], execution);

        assertThat(first.getHeaders().getFirst(HmacSignatureVerificationFilter.NONCE_HEADER))
                .isNotEqualTo(second.getHeaders().getFirst(HmacSignatureVerificationFilter.NONCE_HEADER));
    }

    private static final class TestHttpRequest implements HttpRequest {

        private final HttpMethod method;
        private final URI uri;
        private final HttpHeaders headers = new HttpHeaders();

        private TestHttpRequest(HttpMethod method, URI uri) {
            this.method = method;
            this.uri = uri;
        }

        @Override
        public HttpMethod getMethod() {
            return method;
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
