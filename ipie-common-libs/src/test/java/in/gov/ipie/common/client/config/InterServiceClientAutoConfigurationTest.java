package in.gov.ipie.common.client.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.security.access.AccessDeniedException;

import in.gov.ipie.common.client.locale.LocalePropagationInterceptor;
import in.gov.ipie.common.security.hmac.HmacSignatureVerificationFilter;
import in.gov.ipie.common.security.hmac.HmacSigningProperties;

/**
 * Proves {@code InterServiceClientAutoConfiguration#interServiceSecurityInterceptor}'s additive
 * composition: HMAC signing on top of the mode-selected interceptor when enabled with a valid
 * key (fail-fast {@link IllegalStateException} at bean-creation time otherwise - see that
 * method's Javadoc for why this one case fails eagerly rather than being deferred to first call),
 * and OPA-based authorization composed outermost of all of it when enabled.
 */
class InterServiceClientAutoConfigurationTest {

    private final InterServiceClientAutoConfiguration autoConfiguration = new InterServiceClientAutoConfiguration();
    private final Environment environment = new StandardEnvironment();

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void localePropagationIsComposedOnTheModeSelectedInterceptor() throws Exception {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("hi"));
        InterServiceSecurityProperties securityProperties = new InterServiceSecurityProperties();
        securityProperties.setMode(InterServiceSecurityProperties.Mode.NONE);

        ClientHttpRequestInterceptor interceptor = autoConfiguration.interServiceSecurityInterceptor(
                securityProperties, new HmacSigningProperties(), new OpaAuthorizationProperties(),
                emptyProvider(), emptyProvider(), new LocalePropagationInterceptor(), environment);

        TestHttpRequest request = new TestHttpRequest();
        interceptor.intercept(request, new byte[0], (req, body) -> null);

        assertThat(request.getHeaders().getFirst(HttpHeaders.ACCEPT_LANGUAGE)).isEqualTo("hi");
    }

    @Test
    void hmacSigningDisabled_returnsTheModeSelectedInterceptorUnchanged() throws Exception {
        InterServiceSecurityProperties securityProperties = new InterServiceSecurityProperties();
        securityProperties.setMode(InterServiceSecurityProperties.Mode.NONE);

        ClientHttpRequestInterceptor interceptor = autoConfiguration.interServiceSecurityInterceptor(
                securityProperties, new HmacSigningProperties(), new OpaAuthorizationProperties(),
                emptyProvider(), emptyProvider(), new LocalePropagationInterceptor(), environment);

        TestHttpRequest request = new TestHttpRequest();
        interceptor.intercept(request, new byte[0], (req, body) -> null);

        assertThat(request.getHeaders().getFirst(HmacSignatureVerificationFilter.SIGNATURE_HEADER)).isNull();
    }

    @Test
    void hmacSigningEnabledWithAValidKey_addsSignatureHeadersOnTopOfTheModeSelectedInterceptor() throws Exception {
        InterServiceSecurityProperties securityProperties = new InterServiceSecurityProperties();
        securityProperties.setMode(InterServiceSecurityProperties.Mode.NONE);
        securityProperties.setHmacSigningEnabled(true);
        securityProperties.setHmacSigningKeyId("test-key");
        HmacSigningProperties hmacSigningProperties = new HmacSigningProperties();
        hmacSigningProperties.setKeys(Map.of("test-key", "shared-secret"));

        ClientHttpRequestInterceptor interceptor = autoConfiguration.interServiceSecurityInterceptor(
                securityProperties, hmacSigningProperties, new OpaAuthorizationProperties(),
                emptyProvider(), emptyProvider(), new LocalePropagationInterceptor(), environment);

        TestHttpRequest request = new TestHttpRequest();
        interceptor.intercept(request, new byte[0], (req, body) -> null);

        assertThat(request.getHeaders().getFirst(HmacSignatureVerificationFilter.SIGNATURE_HEADER)).isNotBlank();
        assertThat(request.getHeaders().getFirst(HmacSignatureVerificationFilter.KEY_ID_HEADER)).isEqualTo("test-key");
    }

    @Test
    void hmacSigningEnabledWithoutAMatchingKey_failsFastAtBeanCreation() {
        InterServiceSecurityProperties securityProperties = new InterServiceSecurityProperties();
        securityProperties.setMode(InterServiceSecurityProperties.Mode.NONE);
        securityProperties.setHmacSigningEnabled(true);
        securityProperties.setHmacSigningKeyId("missing-key");

        assertThatThrownBy(() -> autoConfiguration.interServiceSecurityInterceptor(
                securityProperties, new HmacSigningProperties(), new OpaAuthorizationProperties(),
                emptyProvider(), emptyProvider(), new LocalePropagationInterceptor(), environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing-key");
    }

    @Test
    void opaEnabledAndUnreachable_failsClosedOutermostOfEverythingElse() throws Exception {
        InterServiceSecurityProperties securityProperties = new InterServiceSecurityProperties();
        securityProperties.setMode(InterServiceSecurityProperties.Mode.NONE);
        OpaAuthorizationProperties opaProperties = new OpaAuthorizationProperties();
        opaProperties.setEnabled(true);
        opaProperties.setUrl("http://localhost:1");

        ClientHttpRequestInterceptor interceptor = autoConfiguration.interServiceSecurityInterceptor(
                securityProperties, new HmacSigningProperties(), opaProperties,
                emptyProvider(), emptyProvider(), new LocalePropagationInterceptor(), environment);

        assertThatThrownBy(() -> interceptor.intercept(new TestHttpRequest(), new byte[0], (req, body) -> null))
                .isInstanceOf(AccessDeniedException.class);
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                throw new IllegalStateException("no bean of this type in this test");
            }

            @Override
            public T getIfAvailable() {
                return null;
            }

            @Override
            public T getIfUnique() {
                return null;
            }
        };
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
