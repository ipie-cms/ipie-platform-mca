package in.gov.ipie.common.security.hmac;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Verifies the {@code X-Signature}/{@code X-Timestamp}/{@code X-Nonce}/{@code X-Signing-Key-Id}
 * headers {@code common-client}'s {@code HmacSigningInterceptor} adds, for every path matching
 * {@link HmacSigningProperties#getProtectedPaths()} - defense in depth for genuinely
 * high-sensitivity regulatory calls, on top of whatever transport/token-based security already
 * applies (Development_Environment_Configuration.md, Section 15 checklist item: request signing
 * for CIRP/Liquidation/PGIRP-style actions, and nonce+timestamp replay protection).
 *
 * <p><b>Not registered by any auto-configuration - opt-in only</b>, the same reasoning as
 * {@code RefreshTokenCaptureFilter}: a service adds this itself
 * (e.g. {@code http.addFilterBefore(new HmacSignatureVerificationFilter(...), ...)} in its own
 * {@code SecurityFilterChain}) only once it has identified which endpoints actually need this
 * extra control - most endpoints should rely on the platform's standard JWT/mTLS security instead.
 *
 * <p>A request to a non-protected path passes through unchanged. A request to a protected path
 * that is missing a header, carries an unparseable timestamp, falls outside
 * {@link HmacSigningProperties#getClockSkewTolerance()}, names an unknown {@code X-Signing-Key-Id},
 * fails signature verification, or replays an already-consumed nonce is rejected with
 * {@code 401} - the filter writes the response directly and does not call {@code doFilter}, since
 * this can run ahead of - and independent of - JWT authentication in the chain.
 */
public class HmacSignatureVerificationFilter extends OncePerRequestFilter {

    public static final String SIGNATURE_HEADER = "X-Signature";
    public static final String TIMESTAMP_HEADER = "X-Timestamp";
    public static final String NONCE_HEADER = "X-Nonce";
    public static final String KEY_ID_HEADER = "X-Signing-Key-Id";

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final HmacSigningProperties properties;
    private final NonceStore nonceStore;
    private final MeterRegistry meterRegistry;

    public HmacSignatureVerificationFilter(HmacSigningProperties properties, NonceStore nonceStore, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.nonceStore = nonceStore;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!isProtected(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        // Wrapped so the body can be read here for hashing AND still be read again downstream
        // (by the actual controller) - a plain HttpServletRequest's input stream can only be
        // consumed once; see CachedBodyHttpServletRequest's Javadoc for why
        // ContentCachingRequestWrapper does not solve this on its own.
        CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request);

        String rejectionReason = verify(wrappedRequest, wrappedRequest.cachedBody());
        if (rejectionReason != null) {
            meterRegistry.counter("ipie.hmac.verification.failed", "reason", rejectionReason).increment();
            respondUnauthorized(response, rejectionReason);
            return;
        }

        filterChain.doFilter(wrappedRequest, response);
    }

    /**
     * @return a short, stable, bounded-cardinality reason code (safe as a Micrometer tag value -
     *     never the raw, dynamic {@code keyId}/free text) if rejected, {@code null} if valid
     */
    private String verify(HttpServletRequest request, byte[] body) {
        String signature = request.getHeader(SIGNATURE_HEADER);
        String timestampHeader = request.getHeader(TIMESTAMP_HEADER);
        String nonce = request.getHeader(NONCE_HEADER);
        String keyId = request.getHeader(KEY_ID_HEADER);

        if (signature == null || timestampHeader == null || nonce == null || keyId == null) {
            return "missing_headers";
        }

        String secret = properties.getKeys().get(keyId);
        if (secret == null) {
            return "unknown_key_id";
        }

        Instant timestamp;
        try {
            timestamp = Instant.parse(timestampHeader);
        } catch (DateTimeParseException e) {
            return "unparseable_timestamp";
        }
        Duration drift = Duration.between(timestamp, Instant.now()).abs();
        if (drift.compareTo(properties.getClockSkewTolerance()) > 0) {
            return "expired_timestamp";
        }

        boolean valid = HmacSignature.verify(
                secret, request.getMethod(), request.getRequestURI(), timestampHeader, nonce, body, signature);
        if (!valid) {
            return "invalid_signature";
        }

        if (!nonceStore.tryConsume(nonce, properties.getNonceTtl())) {
            return "replayed_nonce";
        }

        return null;
    }

    private boolean isProtected(String requestUri) {
        List<String> protectedPaths = properties.getProtectedPaths();
        return protectedPaths.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, requestUri));
    }

    private static void respondUnauthorized(HttpServletResponse response, String reason) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"INVALID_SIGNATURE\",\"message\":\"" + reason + "\"}");
    }
}
