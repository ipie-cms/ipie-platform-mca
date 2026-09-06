package in.gov.ipie.common.client.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.core.io.DefaultResourceLoader;
import org.slf4j.MDC;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestInterceptor;

import in.gov.ipie.common.client.correlation.CorrelationPropagationInterceptor;
import in.gov.ipie.common.core.correlation.CorrelationConstants;
import in.gov.ipie.common.resilience.config.IpieResilienceHttpProperties;

/**
 * Two things about the mTLS wiring are worth a test of their own, because both fail silently.
 *
 * <p>The first is that replacing the {@code RestClient.Builder}'s request factory to attach the
 * SSL bundle does not throw away common-resilience's connect/response timeouts - a
 * {@code requestFactory(...)} call replaces rather than decorates, so the natural implementation
 * quietly turns every inter-service call into an unbounded one at the moment mTLS is switched on.
 * Nothing would fail; calls would just hang forever under a fault that used to time out.
 *
 * <p>The second is that composing correlation and security into a single interceptor (done so
 * {@code interServiceClient} stays inside Checkstyle's parameter limit once the mTLS factory is
 * added) preserves the order the two separate registrations had.
 */
class InterServiceMtlsConfigurationTest {

    private static final String PASSWORD = "changeit";

    private final InterServiceClientAutoConfiguration autoConfiguration = new InterServiceClientAutoConfiguration();

    @TempDir
    private Path certificateDirectory;

    @Test
    void theMtlsRequestFactoryCarriesTheSslBundleAndKeepsTheResilienceTimeouts() throws Exception {
        InterServiceMtlsProperties mtlsProperties = new InterServiceMtlsProperties();
        mtlsProperties.setEnabled(true);
        mtlsProperties.setKeyStore(writeCertificateStore().toUri().toString());
        mtlsProperties.setKeyStorePassword(PASSWORD);

        IpieResilienceHttpProperties httpProperties = new IpieResilienceHttpProperties();
        httpProperties.setConnectTimeout(Duration.ofSeconds(3));
        httpProperties.setResponseTimeout(Duration.ofSeconds(7));

        ClientHttpRequestFactorySettings settings = InterServiceClientAutoConfiguration.InterServiceMtlsConfiguration
                .settings(mtlsProperties, httpProperties, new DefaultResourceLoader());

        assertThat(settings.sslBundle()).isNotNull();
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(settings.readTimeout()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void correlationStillRunsAheadOfSecurityOnceTheTwoAreComposed() throws Exception {
        ClientHttpRequestInterceptor security = (request, body, execution) -> {
            // Asserting from inside the security interceptor is the point: it can only see the
            // correlation header if correlation genuinely ran first.
            assertThat(request.getHeaders().getFirst(CorrelationConstants.CORRELATION_ID_HEADER)).isNotNull();
            request.getHeaders().add("X-Ran-Second", "true");
            return execution.execute(request, body);
        };

        ClientHttpRequestInterceptor composed = autoConfiguration.interServiceRequestInterceptor(
                new CorrelationPropagationInterceptor(), security);

        MDC.put(CorrelationConstants.CORRELATION_ID_MDC_KEY, "test-correlation-id");
        try {
            TestHttpRequest request = new TestHttpRequest();
            composed.intercept(request, new byte[0], (req, body) -> null);

            HttpHeaders headers = request.getHeaders();
            assertThat(headers.getFirst(CorrelationConstants.CORRELATION_ID_HEADER)).isEqualTo("test-correlation-id");
            assertThat(headers.getFirst("X-Ran-Second")).isEqualTo("true");
        } finally {
            MDC.clear();
        }
    }

    private Path writeCertificateStore() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setCertificateEntry("identity", systemCaCertificate());
        Path file = certificateDirectory.resolve("identity.p12");
        try (OutputStream out = Files.newOutputStream(file)) {
            store.store(out, PASSWORD.toCharArray());
        }
        return file;
    }

    /** See {@code InterServiceMtlsSslBundlesTest} for why the fixtures are borrowed from the JVM's own trust anchors. */
    private static X509Certificate systemCaCertificate() throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        for (TrustManager trustManager : factory.getTrustManagers()) {
            if (trustManager instanceof X509TrustManager x509TrustManager) {
                List<X509Certificate> issuers = List.of(x509TrustManager.getAcceptedIssuers());
                if (!issuers.isEmpty()) {
                    return issuers.get(0);
                }
            }
        }
        throw new IllegalStateException("This JVM trusts no CA certificates, so the test fixture cannot be built.");
    }

    /** A copy of {@code InterServiceClientAutoConfigurationTest}'s, which is private to that class. */
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
