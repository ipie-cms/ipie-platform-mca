package in.gov.ipie.common.web.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Small, shared HTTP-request helpers with more than one call site in this codebase - kept here
 * rather than duplicated, the same "shared cross-cutting concern" reasoning every other
 * common-libs package documents for itself.
 */
public final class HttpRequestUtils {

    private HttpRequestUtils() {
    }

    /**
     * The caller's IP, for the audit trail ({@code common-audit}'s {@code AuditAspect}) and for the
     * rate limiter's bucket key ({@code RateLimitFilter}).
     *
     * <p>Deliberately does <em>not</em> read {@code X-Forwarded-For}. It used to take the header's
     * first entry, which is the part a client writes - so a caller chose the IP that was recorded
     * against their own actions and the bucket they were throttled in. Rotating the header
     * defeated the limiter completely on the public, unauthenticated endpoints it protects.
     *
     * <p>The header is now resolved by Tomcat's {@code RemoteIpValve} before the request reaches
     * any application code ({@code server.forward-headers-strategy: NATIVE}, see
     * {@code ipie-security-defaults.yml}). That walks the header right-to-left and discards
     * trusted-proxy entries, so a client-supplied value never survives, and by this point
     * {@code getRemoteAddr()} already holds the resolved address. With no trusted proxies
     * configured it holds the direct peer - unhelpful but honest, which is the right way round for
     * both a security control and an audit record.
     */
    public static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
