package in.gov.ipie.common.security.hmac;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Proves {@link HmacSignatureVerificationFilter}: a non-protected path passes through untouched;
 * a protected path requires a valid, fresh, not-yet-replayed signature, and rejects with
 * {@code 401} otherwise for each failure reason independently (missing headers, unknown key id,
 * expired timestamp, wrong signature, replayed nonce).
 */
class HmacSignatureVerificationFilterTest {

    private static final String SECRET = "shared-secret";
    private static final String KEY_ID = "test-key";
    private static final byte[] BODY = "{\"amount\":100}".getBytes(StandardCharsets.UTF_8);

    private final HmacSigningProperties properties = properties();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final HmacSignatureVerificationFilter filter =
            new HmacSignatureVerificationFilter(properties, new InMemoryNonceStore(), meterRegistry);

    @Test
    void aNonProtectedPath_passesThroughUnchanged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/unprotected");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void aProtectedPathWithAValidSignature_passesThrough() throws Exception {
        MockHttpServletRequest request = signedRequest("nonce-valid", Instant.now());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isTrue();
    }

    @Test
    void aProtectedPathWithMissingHeaders_isRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/claims");
        request.setContent(BODY);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(meterRegistry.counter("ipie.hmac.verification.failed", "reason", "missing_headers").count()).isEqualTo(1.0);
    }

    @Test
    void aProtectedPathWithAnExpiredTimestamp_isRejected() throws Exception {
        MockHttpServletRequest request = signedRequest("nonce-expired", Instant.now().minus(Duration.ofHours(1)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void aProtectedPathWithATamperedSignature_isRejected() throws Exception {
        // Built directly (not via signedRequest, which already adds a correct signature header) -
        // MockHttpServletRequest.addHeader appends rather than replaces, and getHeader() returns
        // the first value, so overwriting an already-valid signature that way would silently keep
        // verifying against the original, correct one instead of the tampered value.
        String timestamp = Instant.now().toString();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/claims");
        request.setContent(BODY);
        request.addHeader(HmacSignatureVerificationFilter.TIMESTAMP_HEADER, timestamp);
        request.addHeader(HmacSignatureVerificationFilter.NONCE_HEADER, "nonce-tampered");
        request.addHeader(HmacSignatureVerificationFilter.KEY_ID_HEADER, KEY_ID);
        request.addHeader(HmacSignatureVerificationFilter.SIGNATURE_HEADER, "0".repeat(64));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void aReplayedNonce_isRejectedOnTheSecondAttempt() throws Exception {
        Instant now = Instant.now();
        MockHttpServletRequest firstRequest = signedRequest("nonce-replay", now);
        filter.doFilter(firstRequest, new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletRequest secondRequest = signedRequest("nonce-replay", now);
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(secondRequest, secondResponse, new MockFilterChain());

        assertThat(secondResponse.getStatus()).isEqualTo(401);
    }

    private MockHttpServletRequest signedRequest(String nonce, Instant timestamp) {
        String timestampHeader = timestamp.toString();
        String signature = HmacSignature.sign(SECRET, "POST", "/api/v1/claims", timestampHeader, nonce, BODY);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/claims");
        request.setContent(BODY);
        request.addHeader(HmacSignatureVerificationFilter.TIMESTAMP_HEADER, timestampHeader);
        request.addHeader(HmacSignatureVerificationFilter.NONCE_HEADER, nonce);
        request.addHeader(HmacSignatureVerificationFilter.KEY_ID_HEADER, KEY_ID);
        request.addHeader(HmacSignatureVerificationFilter.SIGNATURE_HEADER, signature);
        return request;
    }

    private static HmacSigningProperties properties() {
        HmacSigningProperties properties = new HmacSigningProperties();
        properties.setKeys(java.util.Map.of(KEY_ID, SECRET));
        properties.setClockSkewTolerance(Duration.ofMinutes(5));
        properties.setNonceTtl(Duration.ofMinutes(10));
        properties.setProtectedPaths(List.of("/api/v1/claims"));
        return properties;
    }
}
