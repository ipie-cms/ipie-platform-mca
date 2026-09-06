package in.gov.ipie.common.client.locale;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;

/**
 * Proves the locale resolved for the current (inbound) request is carried onto the outbound call
 * so the downstream service's own SupportedLocaleResolver sees it, and that no header is sent
 * when nothing explicitly set a locale context - mirrors CorrelationPropagationInterceptorTest.
 */
class LocalePropagationInterceptorTest {

    private final LocalePropagationInterceptor interceptor = new LocalePropagationInterceptor();

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void propagatesTheCurrentLocaleAsAnAcceptLanguageHeader() throws Exception {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("hi"));

        TestHttpRequest request = new TestHttpRequest();
        ClientHttpRequestExecution execution = (req, body) -> null;

        interceptor.intercept(request, new byte[0], execution);

        assertThat(request.getHeaders().getFirst(HttpHeaders.ACCEPT_LANGUAGE)).isEqualTo("hi");
    }

    @Test
    void noLocaleContextExplicitlySet_sendsNoHeader() throws Exception {
        TestHttpRequest request = new TestHttpRequest();
        ClientHttpRequestExecution execution = (req, body) -> null;

        interceptor.intercept(request, new byte[0], execution);

        assertThat(request.getHeaders().getFirst(HttpHeaders.ACCEPT_LANGUAGE)).isNull();
    }

    private static final class TestHttpRequest implements HttpRequest {

        private final HttpHeaders headers = new HttpHeaders();

        @Override
        public HttpMethod getMethod() {
            return HttpMethod.GET;
        }

        @Override
        public URI getURI() {
            return URI.create("http://ipie-communication-service:8080/api/v1/notifications");
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
