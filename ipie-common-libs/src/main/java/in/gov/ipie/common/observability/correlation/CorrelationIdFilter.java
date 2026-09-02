package in.gov.ipie.common.observability.correlation;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import in.gov.ipie.common.core.correlation.CorrelationConstants;

/**
 * Reads {@code X-Correlation-Id} off the inbound request (generating one if absent), publishes it
 * to the SLF4J MDC for the lifetime of the request and echoes it back on the response - the basis
 * for correlating log lines across every service a request touches (master standards doc, 5.6).
 * Runs first in the filter chain so every downstream filter, including security, can log with a
 * correlation id already in place.
 */
public class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

    // A correlation id is only ever echoed back verbatim into a response header, so an inbound
    // value that doesn't match this shape is untrusted input and must be replaced, not forwarded
    // (avoids CRLF/header-injection via a crafted X-Correlation-Id request header).
    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = sanitize(request.getHeader(CorrelationConstants.CORRELATION_ID_HEADER));

        try {
            LoggingContext.putCorrelationId(correlationId);
            response.setHeader(CorrelationConstants.CORRELATION_ID_HEADER, correlationId);
            filterChain.doFilter(request, response);
        } finally {
            LoggingContext.clear();
        }
    }

    private static String sanitize(String candidate) {
        if (candidate != null && SAFE_CORRELATION_ID.matcher(candidate).matches()) {
            return candidate;
        }
        // Random on purpose, not IdGenerator.newUuid(). A correlation id is not a primary key, so
        // time ordering buys no index locality here - and it travels in headers and logs that leave
        // this platform, where an embedded timestamp would disclose request timing for nothing.
        return UUID.randomUUID().toString();
    }
}
